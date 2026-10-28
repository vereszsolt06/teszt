package hu.sporolos.data;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
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
 *
 * Folyamatos (StAX) olvasással dolgozik, így a 25 000 soros napi Árfigyelő-fájlt is
 * néhány tíz MB memóriával, kb. fél másodperc alatt beolvassa (a DOM-os változat ~250 MB-ot evett).
 * Az első munkalapot adja vissza, soronként, szöveges cellákkal (az üres cellák helyén "").
 */
public final class XlsxReader {

    private XlsxReader() {}

    /** Egy munkalap: neve és cellái. */
    public record Sheet(String name, List<List<String>> rows) {}

    /** Soronkénti feldolgozó. */
    @FunctionalInterface
    public interface RowHandler {
        void row(int index, List<String> cells);
    }

    /** Az egész első munkalap memóriába olvasva (kisebb fájlokhoz, tesztekhez). */
    public static Sheet readFirstSheet(InputStream in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        String name = streamFirstSheet(in, (i, cells) -> rows.add(cells));
        return new Sheet(name, rows);
    }

    /**
     * Az első munkalap sorait egyenként adja át a feldolgozónak.
     *
     * @return a munkalap neve
     */
    public static String streamFirstSheet(InputStream in, RowHandler handler) throws IOException {
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
        } catch (java.util.zip.ZipException ex) {
            throw new IOException("Nem érvényes xlsx (ZIP) fájl: " + ex.getMessage(), ex);
        }
        if (files.keySet().stream().noneMatch(k -> k.startsWith("xl/worksheets/sheet"))) {
            throw new IOException("Nem xlsx fájl (nincs benne munkalap)");
        }
        try {
            List<String> shared = files.containsKey("xl/sharedStrings.xml")
                    ? readSharedStrings(files.get("xl/sharedStrings.xml")) : List.of();
            String[] first = firstSheet(files);
            byte[] sheet = files.get(first[1]);
            if (sheet == null) throw new IOException("Nem található az első munkalap");
            readSheet(sheet, shared, handler);
            return first[0];
        } catch (XMLStreamException e) {
            throw new IOException("Hibás xlsx: " + e.getMessage(), e);
        }
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        // biztonság: külső entitások és DTD tiltása (XXE)
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static List<String> readSharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        StringBuilder sb = null;
        boolean inT = false, inPhonetic = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("si")) sb = new StringBuilder();
                else if (n.equals("rPh")) inPhonetic = true; // fonetikus segédszöveg – nem része az értéknek
                else if (n.equals("t")) inT = true;
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                if (inT && !inPhonetic && sb != null) sb.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("t")) inT = false;
                else if (n.equals("rPh")) inPhonetic = false;
                else if (n.equals("si") && sb != null) { out.add(sb.toString()); sb = null; }
            }
        }
        return out;
    }

    /** @return [munkalap neve, útvonal a ZIP-ben] */
    private static String[] firstSheet(Map<String, byte[]> files) throws XMLStreamException {
        String name = "", rid = null;
        if (files.containsKey("xl/workbook.xml")) {
            XMLStreamReader r = reader(files.get("xl/workbook.xml"));
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    name = attr(r, "name");
                    rid = attr(r, "id"); // r:id
                    break;
                }
            }
        }
        if (rid != null && files.containsKey("xl/_rels/workbook.xml.rels")) {
            XMLStreamReader r = reader(files.get("xl/_rels/workbook.xml.rels"));
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                        && rid.equals(attr(r, "Id"))) {
                    String target = attr(r, "Target");
                    String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    if (files.containsKey(path)) return new String[]{name, path};
                }
            }
        }
        String path = files.containsKey("xl/worksheets/sheet1.xml") ? "xl/worksheets/sheet1.xml"
                : files.keySet().stream().filter(k -> k.startsWith("xl/worksheets/sheet")).sorted().findFirst().orElse(null);
        return new String[]{name, path};
    }

    private static String attr(XMLStreamReader r, String local) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(local)) return r.getAttributeValue(i);
        }
        return null;
    }

    private static void readSheet(byte[] xml, List<String> shared, RowHandler handler) throws XMLStreamException {
        XMLStreamReader r = reader(xml);
        List<String> row = null;
        int rowIndex = 0;
        String cellType = null;
        int cellCol = 0;
        StringBuilder value = null;   // <v> vagy inline <t> tartalma
        boolean capture = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new ArrayList<>();
                    case "c" -> {
                        cellType = attr(r, "t");
                        cellCol = columnIndex(attr(r, "r"), row == null ? 0 : row.size());
                        value = new StringBuilder();
                    }
                    case "v", "t" -> capture = value != null;
                    default -> { }
                }
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && capture) {
                value.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> capture = false;
                    case "c" -> {
                        if (row != null) {
                            while (row.size() < cellCol) row.add("");
                            row.add(cellText(cellType, value == null ? "" : value.toString(), shared));
                        }
                        value = null;
                    }
                    case "row" -> {
                        if (row != null) handler.row(rowIndex++, row);
                        row = null;
                    }
                    default -> { }
                }
            }
        }
    }

    private static String cellText(String type, String raw, List<String> shared) {
        if ("s".equals(type)) {
            try {
                return shared.get(Integer.parseInt(raw.trim()));
            } catch (RuntimeException e) {
                return "";
            }
        }
        return raw;
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
}
