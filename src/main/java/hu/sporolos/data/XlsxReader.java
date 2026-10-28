package hu.sporolos.data;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Külső könyvtár nélküli .xlsx olvasó: az xlsx egy ZIP, benne XML-ekkel.
 * Az első munkalap celláit adja vissza szövegként, sorok listájaként (az üres cellák helyén "").
 */
public final class XlsxReader {

    private XlsxReader() {}

    /** Egy munkalap: neve és cellái. */
    public record Sheet(String name, List<List<String>> rows) {}

    public static Sheet readFirstSheet(InputStream in) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml") || n.equals("xl/workbook.xml")
                        || n.equals("xl/_rels/workbook.xml.rels") || n.startsWith("xl/worksheets/sheet")) {
                    files.put(n, zip.readAllBytes());
                }
            }
        }
        if (files.isEmpty()) throw new IOException("Nem xlsx fájl (nincs benne munkalap)");

        try {
            List<String> shared = new ArrayList<>();
            if (files.containsKey("xl/sharedStrings.xml")) {
                Document d = parse(files.get("xl/sharedStrings.xml"));
                NodeList sis = d.getElementsByTagNameNS("*", "si");
                for (int i = 0; i < sis.getLength(); i++) {
                    // egy <si> több <t> darabból is állhat (formázott szöveg)
                    NodeList ts = ((Element) sis.item(i)).getElementsByTagNameNS("*", "t");
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < ts.getLength(); j++) sb.append(ts.item(j).getTextContent());
                    shared.add(sb.toString());
                }
            }

            String sheetName = firstSheetName(files);
            byte[] sheet = files.get(firstSheetPath(files));
            if (sheet == null) throw new IOException("Nem található az első munkalap");
            Document d = parse(sheet);
            List<List<String>> rows = new ArrayList<>();
            NodeList rowNodes = d.getElementsByTagNameNS("*", "row");
            for (int r = 0; r < rowNodes.getLength(); r++) {
                List<String> row = new ArrayList<>();
                NodeList cells = ((Element) rowNodes.item(r)).getElementsByTagNameNS("*", "c");
                for (int c = 0; c < cells.getLength(); c++) {
                    Element cell = (Element) cells.item(c);
                    int col = columnIndex(cell.getAttribute("r"), row.size());
                    while (row.size() < col) row.add("");
                    row.add(cellValue(cell, shared));
                }
                rows.add(row);
            }
            return new Sheet(sheetName, rows);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Hibás xlsx: " + e.getMessage(), e);
        }
    }

    private static String firstSheetName(Map<String, byte[]> files) throws Exception {
        if (!files.containsKey("xl/workbook.xml")) return "";
        NodeList sheets = parse(files.get("xl/workbook.xml")).getElementsByTagNameNS("*", "sheet");
        return sheets.getLength() > 0 ? ((Element) sheets.item(0)).getAttribute("name") : "";
    }

    private static String firstSheetPath(Map<String, byte[]> files) throws Exception {
        // a workbook.xml első <sheet> elemének r:id-ját a rels fájlból oldjuk fel
        if (files.containsKey("xl/workbook.xml") && files.containsKey("xl/_rels/workbook.xml.rels")) {
            Document wb = parse(files.get("xl/workbook.xml"));
            NodeList sheets = wb.getElementsByTagNameNS("*", "sheet");
            if (sheets.getLength() > 0) {
                Element s = (Element) sheets.item(0);
                String rid = s.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                Document rels = parse(files.get("xl/_rels/workbook.xml.rels"));
                NodeList rs = rels.getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < rs.getLength(); i++) {
                    Element rel = (Element) rs.item(i);
                    if (rel.getAttribute("Id").equals(rid)) {
                        String target = rel.getAttribute("Target");
                        if (target.startsWith("/")) return target.substring(1);
                        return "xl/" + target;
                    }
                }
            }
        }
        return files.containsKey("xl/worksheets/sheet1.xml") ? "xl/worksheets/sheet1.xml"
                : files.keySet().stream().filter(k -> k.startsWith("xl/worksheets/sheet")).sorted().findFirst().orElse(null);
    }

    private static String cellValue(Element cell, List<String> shared) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList ts = cell.getElementsByTagNameNS("*", "t");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ts.getLength(); i++) sb.append(ts.item(i).getTextContent());
            return sb.toString();
        }
        NodeList vs = cell.getElementsByTagNameNS("*", "v");
        if (vs.getLength() == 0) return "";
        String v = vs.item(0).getTextContent();
        if ("s".equals(type)) {
            try {
                return shared.get(Integer.parseInt(v.trim()));
            } catch (RuntimeException e) {
                return "";
            }
        }
        return v;
    }

    /** "C12" → 2 (0-alapú oszlopindex). Ha nincs hivatkozás, a következő oszlop. */
    static int columnIndex(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) return fallback;
        int col = 0;
        for (char ch : ref.toCharArray()) {
            if (Character.isLetter(ch)) col = col * 26 + (Character.toUpperCase(ch) - 'A' + 1);
            else break;
        }
        return col == 0 ? fallback : col - 1;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // biztonság: külső entitások tiltása (XXE)
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml));
    }

}
