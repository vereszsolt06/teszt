package hu.sporolos.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimális PostgreSQL-kliens a 3-as wire protokollal, külső JDBC-driver nélkül
 * (a projekt szándékosan függőségmentes, és egyetlen JAR-ként fut).
 *
 * Tudja: jelszavas belépés (SCRAM-SHA-256, MD5, sima), paraméterezett lekérdezés a kiterjesztett
 * protokollal (Parse/Bind/Execute – így nincs SQL-befecskendezés), több utasításos egyszerű lekérdezés
 * (sémához), tranzakció. Szöveges formátumban küld és fogad; a bytea mezőket hexben adja vissza
 * ({@link #bytea}). TLS nincs: a Docker-hálózaton belüli vagy helyi kapcsolatra készült.
 *
 * Egy példány egy kapcsolat, nem szálbiztos – a {@link PgStorage} egy kis készletet tart belőle.
 */
public final class PgClient implements AutoCloseable {

    /** Adatbázis-hiba a szerver üzenetével és SQLSTATE kódjával. */
    public static final class PgException extends IOException {
        public final String sqlState;

        PgException(String message, String sqlState) {
            super(message + (sqlState == null ? "" : " [" + sqlState + "]"));
            this.sqlState = sqlState;
        }
    }

    /** Egy lekérdezés eredménye: oszlopnevek és sorok (szövegként; null = SQL NULL). */
    public record Result(List<String> columns, List<String[]> rows, String tag) {
        public int updated() {
            String[] p = tag == null ? new String[0] : tag.split(" ");
            try {
                return p.length == 0 ? 0 : Integer.parseInt(p[p.length - 1]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public List<Map<String, String>> maps() {
            List<Map<String, String>> out = new ArrayList<>();
            for (String[] r : rows) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) m.put(columns.get(i), r[i]);
                out.add(m);
            }
            return out;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private boolean broken;

    private PgClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        this.out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    /** Kapcsolódás: postgres://felhasznalo:jelszo@host:5432/adatbazis */
    public static PgClient connect(String url) throws IOException {
        URI u = URI.create(url.replaceFirst("^postgresql:", "postgres:"));
        if (!"postgres".equals(u.getScheme())) throw new IOException("Nem postgres:// cím: " + url);
        String user = "postgres", password = "";
        if (u.getRawUserInfo() != null) {
            String[] ui = u.getRawUserInfo().split(":", 2);
            user = URLDecoder.decode(ui[0], StandardCharsets.UTF_8);
            if (ui.length > 1) password = URLDecoder.decode(ui[1], StandardCharsets.UTF_8);
        }
        String db = u.getPath() == null || u.getPath().length() <= 1 ? user : u.getPath().substring(1);
        int port = u.getPort() > 0 ? u.getPort() : 5432;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost() == null ? "localhost" : u.getHost(), port), 5000);
        s.setSoTimeout(30_000);
        s.setTcpNoDelay(true);
        PgClient c = new PgClient(s);
        try {
            c.startup(user, password, db);
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
        return c;
    }

    public boolean broken() {
        return broken || socket.isClosed();
    }

    // ---------- lekérdezések ----------

    /** Paraméterezett lekérdezés ($1, $2 …). A paraméterek szövegként mennek (null = NULL, byte[] = bytea). */
    public Result query(String sql, Object... params) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            // Parse: névtelen utasítás, a típusokat a szerver következteti ki
            msg(b, 'P', cstr("") , cstr(sql), int16(0));
            ByteArrayOutputStream bind = new ByteArrayOutputStream();
            bind.write(cstr(""));
            bind.write(cstr(""));
            bind.write(int16(0)); // minden paraméter szöveges
            bind.write(int16(params.length));
            for (Object p : params) {
                if (p == null) {
                    bind.write(int32(-1));
                } else {
                    byte[] v = (p instanceof byte[] bytes ? "\\x" + HexFormat.of().formatHex(bytes) : String.valueOf(p))
                            .getBytes(StandardCharsets.UTF_8);
                    bind.write(int32(v.length));
                    bind.write(v);
                }
            }
            bind.write(int16(0)); // eredmény szövegesen
            msg(b, 'B', bind.toByteArray());
            msg(b, 'D', new byte[]{'P'}, cstr(""));
            msg(b, 'E', cstr(""), int32(0));
            msg(b, 'S');
            out.write(b.toByteArray());
            out.flush();
            return readResults();
        } catch (IOException e) {
            if (!(e instanceof PgException)) broken = true;
            throw e;
        }
    }

    /** Egyszerű (paraméter nélküli) lekérdezés, több utasítás is lehet benne – sémához, BEGIN/COMMIT-hoz. */
    public void exec(String sql) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            msg(b, 'Q', cstr(sql));
            out.write(b.toByteArray());
            out.flush();
            readResults();
        } catch (IOException e) {
            if (!(e instanceof PgException)) broken = true;
            throw e;
        }
    }

    private Result readResults() throws IOException {
        List<String> cols = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        String tag = null;
        PgException error = null;
        while (true) {
            int type = in.read();
            if (type < 0) throw new IOException("A PostgreSQL bontotta a kapcsolatot");
            byte[] body = new byte[in.readInt() - 4];
            in.readFully(body);
            switch (type) {
                case 'T' -> {
                    cols.clear();
                    Reader r = new Reader(body);
                    int n = r.int16();
                    for (int i = 0; i < n; i++) {
                        cols.add(r.cstr());
                        r.skip(18);
                    }
                }
                case 'D' -> {
                    Reader r = new Reader(body);
                    int n = r.int16();
                    String[] row = new String[n];
                    for (int i = 0; i < n; i++) {
                        int len = r.int32();
                        row[i] = len < 0 ? null : r.str(len);
                    }
                    rows.add(row);
                }
                case 'C' -> tag = new Reader(body).cstr();
                case 'E' -> error = error(body);
                case 'Z' -> {
                    if (error != null) throw error;
                    return new Result(List.copyOf(cols), rows, tag);
                }
                default -> { /* ParseComplete, BindComplete, NoData, Notice, ParameterStatus… */ }
            }
        }
    }

    // ---------- belépés ----------

    private void startup(String user, String password, String db) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(int32(196608)); // 3.0
        p.write(cstr("user"));
        p.write(cstr(user));
        p.write(cstr("database"));
        p.write(cstr(db));
        p.write(cstr("client_encoding"));
        p.write(cstr("UTF8"));
        p.write(cstr("application_name"));
        p.write(cstr("sporolos-konyha"));
        p.write(0);
        byte[] body = p.toByteArray();
        out.write(int32(body.length + 4));
        out.write(body);
        out.flush();

        Scram scram = null;
        while (true) {
            int type = in.read();
            if (type < 0) throw new IOException("A PostgreSQL bontotta a kapcsolatot belépés közben");
            byte[] b = new byte[in.readInt() - 4];
            in.readFully(b);
            if (type == 'E') throw error(b);
            if (type == 'Z') return;
            if (type != 'R') continue;
            Reader r = new Reader(b);
            int code = r.int32();
            switch (code) {
                case 0 -> { /* AuthenticationOk */ }
                case 3 -> send('p', cstr(password));
                case 5 -> {
                    byte[] salt = r.bytes(4);
                    String inner = md5Hex((password + user).getBytes(StandardCharsets.UTF_8));
                    String outer = md5Hex(concat(inner.getBytes(StandardCharsets.US_ASCII), salt));
                    send('p', cstr("md5" + outer));
                }
                case 10 -> {
                    List<String> mechs = new ArrayList<>();
                    for (String m; !(m = r.cstr()).isEmpty(); ) mechs.add(m);
                    if (!mechs.contains("SCRAM-SHA-256")) throw new IOException("Nem támogatott hitelesítés: " + mechs);
                    scram = new Scram(password);
                    byte[] first = scram.clientFirst().getBytes(StandardCharsets.UTF_8);
                    send('p', cstr("SCRAM-SHA-256"), int32(first.length), first);
                }
                case 11 -> {
                    if (scram == null) throw new IOException("Váratlan SASL-üzenet");
                    String fin = scram.clientFinal(new String(r.rest(), StandardCharsets.UTF_8));
                    send('p', fin.getBytes(StandardCharsets.UTF_8));
                }
                case 12 -> {
                    if (scram == null || !scram.verifyServer(new String(r.rest(), StandardCharsets.UTF_8))) {
                        throw new IOException("A PostgreSQL szerver aláírása hibás");
                    }
                }
                default -> throw new IOException("Nem támogatott hitelesítési mód: " + code);
            }
        }
    }

    /** SCRAM-SHA-256 (RFC 5802 / 7677), csatornakötés nélkül. */
    private static final class Scram {
        private final String password;
        private final String nonce;
        private String clientFirstBare, authMessage;
        private byte[] saltedPassword;

        Scram(String password) {
            this.password = password;
            byte[] n = new byte[18];
            new SecureRandom().nextBytes(n);
            this.nonce = Base64.getEncoder().encodeToString(n);
        }

        String clientFirst() {
            clientFirstBare = "n=,r=" + nonce;
            return "n,," + clientFirstBare;
        }

        String clientFinal(String serverFirst) throws IOException {
            Map<String, String> a = attrs(serverFirst);
            String r = a.get("r");
            if (r == null || !r.startsWith(nonce)) throw new IOException("Hibás SCRAM nonce");
            byte[] salt = Base64.getDecoder().decode(a.get("s"));
            int iter = Integer.parseInt(a.get("i"));
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                saltedPassword = f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iter, 256)).getEncoded();
                byte[] clientKey = hmac(saltedPassword, "Client Key");
                byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
                String withoutProof = "c=biws,r=" + r;
                authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
                byte[] sig = hmac(storedKey, authMessage);
                byte[] proof = new byte[clientKey.length];
                for (int i = 0; i < proof.length; i++) proof[i] = (byte) (clientKey[i] ^ sig[i]);
                return withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        boolean verifyServer(String serverFinal) throws IOException {
            String v = attrs(serverFinal).get("v");
            if (v == null) return false;
            try {
                byte[] serverKey = hmac(saltedPassword, "Server Key");
                return MessageDigest.isEqual(hmac(serverKey, authMessage), Base64.getDecoder().decode(v));
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        private static Map<String, String> attrs(String s) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String part : s.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) m.put(part.substring(0, eq), part.substring(eq + 1));
            }
            return m;
        }

        private static byte[] hmac(byte[] key, String data) throws GeneralSecurityException {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------- segédek ----------

    /** A szöveges bytea-érték ("\x0a1b…") bájtjai. */
    public static byte[] bytea(String text) {
        if (text == null) return null;
        if (text.startsWith("\\x")) return HexFormat.of().parseHex(text.substring(2));
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static PgException error(byte[] body) {
        Reader r = new Reader(body);
        String msg = "PostgreSQL-hiba", state = null;
        while (r.pos < body.length) {
            int f = body[r.pos++];
            if (f == 0) break;
            String v = r.cstr();
            if (f == 'M') msg = v;
            if (f == 'C') state = v;
        }
        return new PgException(msg, state);
    }

    private void send(char type, byte[]... parts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        msg(b, type, parts);
        out.write(b.toByteArray());
        out.flush();
    }

    private static void msg(ByteArrayOutputStream b, char type, byte[]... parts) throws IOException {
        int len = 4;
        for (byte[] p : parts) len += p.length;
        b.write(type);
        b.write(int32(len));
        for (byte[] p : parts) b.write(p);
    }

    private static byte[] cstr(String s) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[v.length + 1];
        System.arraycopy(v, 0, r, 0, v.length);
        return r;
    }

    private static byte[] int32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] int16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String md5Hex(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static final class Reader {
        final byte[] b;
        int pos;

        Reader(byte[] b) {
            this.b = b;
        }

        int int32() {
            int v = ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        int int16() {
            int v = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
            pos += 2;
            return v;
        }

        String cstr() {
            int start = pos;
            while (pos < b.length && b[pos] != 0) pos++;
            String s = new String(b, start, pos - start, StandardCharsets.UTF_8);
            pos++;
            return s;
        }

        String str(int len) {
            String s = new String(b, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        byte[] bytes(int len) {
            byte[] r = java.util.Arrays.copyOfRange(b, pos, pos + len);
            pos += len;
            return r;
        }

        byte[] rest() {
            return bytes(b.length - pos);
        }

        void skip(int n) {
            pos += n;
        }
    }

    @Override
    public void close() {
        try {
            if (!socket.isClosed()) {
                out.write('X');
                out.write(int32(4));
                out.flush();
            }
        } catch (IOException ignored) {
            // bezárunk mindenképp
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // nincs teendő
        }
    }
}
