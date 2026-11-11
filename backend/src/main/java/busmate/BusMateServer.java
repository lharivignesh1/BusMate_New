package busmate;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class BusMateServer {
    private static final String DB_URL = System.getenv().getOrDefault(
        "BUSMATE_DB_URL",
        "jdbc:mysql://" + System.getenv().getOrDefault("BUSMATE_DB_HOST", "localhost") + ":" +
        System.getenv().getOrDefault("BUSMATE_DB_PORT", "3306") + "/" +
        System.getenv().getOrDefault("BUSMATE_DB_NAME", "busmate_v2") +
        "?serverTimezone=Asia/Kolkata&sslMode=" +
        System.getenv().getOrDefault("BUSMATE_DB_SSL_MODE", "PREFERRED")
    );
    private static final String DB_USER = System.getenv().getOrDefault("BUSMATE_DB_USER", "root");
    private static final String DB_PASSWORD = System.getenv("BUSMATE_DB_PASSWORD");
    private static final String ADMIN_EMAIL = System.getenv().getOrDefault("BUSMATE_ADMIN_EMAIL", "hariini2526@gmail.com");
    private static final String ADMIN_PASSWORD = System.getenv("BUSMATE_ADMIN_PASSWORD");
    private static final String TELEGRAM_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String TELEGRAM_CHAT_ID = System.getenv("TELEGRAM_CHAT_ID");

    private static final Gson G = new GsonBuilder().serializeNulls().create();
    private static final SecureRandom RNG = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();

    private record Session(int id, String email, String role, String name, String tripType) {
        private Session(int id,String email,String role,String name){this(id,email,role,name,null);}
    }

    private static Connection db() throws SQLException {
        if (DB_PASSWORD == null || DB_PASSWORD.isBlank())
            throw new SQLException("Set BUSMATE_DB_PASSWORD in this terminal before starting BusMate.");
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static void json(HttpExchange ex, int status, Object value) throws IOException {
        byte[] data = G.toJson(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().add("Access-Control-Allow-Credentials", "true");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private static Map<String,Object> body(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length > 100_000) throw new IllegalArgumentException("Request is too large.");
        if (bytes.length == 0) return new HashMap<>();
        Map<String,Object> result = G.fromJson(new String(bytes, StandardCharsets.UTF_8), Map.class);
        return result == null ? new HashMap<>() : result;
    }

    private static String str(Map<?,?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int num(Map<?,?> map, String key, int fallback) {
        try { return (int) Double.parseDouble(str(map, key)); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(Map<?,?> map, String key) {
        Object value = map.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static List<Map<String,Object>> rows(String sql, Object... args) throws SQLException {
        try (Connection c = db(); PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i=1; i<=md.getColumnCount(); i++) {
                        Object value = rs.getObject(i);

                        // MySQL Connector/J may return DATETIME as java.time.LocalDateTime.
                        // Convert JDBC/time values to strings before Gson serializes the response.
                        if (value instanceof java.sql.Time ||
                            value instanceof java.sql.Timestamp ||
                            value instanceof java.time.LocalDateTime ||
                            value instanceof java.time.LocalDate ||
                            value instanceof java.time.LocalTime) {
                            value = value.toString();
                        }

                        row.put(md.getColumnLabel(i), value);
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static int update(String sql, Object... args) throws SQLException {
        try (Connection c = db(); PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            return p.executeUpdate();
        }
    }

    private static int insert(String sql, Object... args) throws SQLException {
        try (Connection c = db(); PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, args);
            p.executeUpdate();
            try (ResultSet rs = p.getGeneratedKeys()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i=0; i<args.length; i++) p.setObject(i+1, args[i]);
    }

    private static Map<String,String> query(String raw) {
        Map<String,String> map = new HashMap<>();
        if (raw != null) for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            try { map.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair.length > 1 ? pair[1] : "", "UTF-8")); }
            catch (Exception ignored) {}
        }
        return map;
    }

    private static String cookie(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers != null) for (String line : headers) for (String part : line.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("BUSMATE_SESSION")) return pair[1];
        }
        return null;
    }

    private static Session session(HttpExchange ex) {
        String token = cookie(ex);
        return token == null ? null : SESSIONS.get(token);
    }

    private static boolean requireRole(HttpExchange ex, String role) throws IOException {
        Session s = session(ex);
        if (s == null) { json(ex, 401, Map.of("message", "Please sign in again.")); return false; }
        if (!s.role().equals(role)) { json(ex, 403, Map.of("message", "Access denied.")); return false; }
        return true;
    }

    private static void login(HttpExchange ex) throws Exception {
        if (!"POST".equals(ex.getRequestMethod())) { json(ex,405,Map.of("message","POST required")); return; }
        Map<String,Object> b = body(ex);
        String role = str(b,"role").toUpperCase(Locale.ROOT);
        String email = str(b,"email").toLowerCase(Locale.ROOT);
        String password = str(b,"password");
        if (!Set.of("STUDENT","DRIVER","ADMIN").contains(role)) {
            json(ex,400,Map.of("message","Choose a valid role.")); return;
        }
        Session s = null;
        if ("ADMIN".equals(role)) {
            if (ADMIN_PASSWORD == null || ADMIN_PASSWORD.isBlank()) {
                json(ex,500,Map.of("message","Set BUSMATE_ADMIN_PASSWORD before starting the server.")); return;
            }
            if (email.equalsIgnoreCase(ADMIN_EMAIL) &&
                MessageDigest.isEqual(sha256(password).getBytes(StandardCharsets.UTF_8),
                                     sha256(ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8))) {
                List<Map<String,Object>> admins = rows("SELECT admin_id,full_name FROM admins WHERE LOWER(email)=LOWER(?) AND active=1", email);
                if (!admins.isEmpty()) s = new Session(((Number)admins.get(0).get("admin_id")).intValue(), email, role, String.valueOf(admins.get(0).get("full_name")));
            }
        } else {
            String table = "STUDENT".equals(role) ? "students" : "drivers";
            String idColumn = "STUDENT".equals(role) ? "student_id" : "driver_id";
            if ("STUDENT".equals(role) && !email.endsWith("@rajalakshmi.edu.in")) {
                json(ex,401,Map.of("message","Use your college email ending in @rajalakshmi.edu.in.")); return;
            }
            List<Map<String,Object>> found = rows("SELECT " + idColumn + " AS id,full_name,email,password_hash" +
                ("DRIVER".equals(role) ? ",trip_type AS tripType,bus_id AS busId" : "") +
                " FROM " + table + " WHERE LOWER(email)=LOWER(?) AND active=1", email);
            if (!found.isEmpty() && MessageDigest.isEqual(sha256(password).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(found.get(0).get("password_hash")).getBytes(StandardCharsets.UTF_8))) {
                if ("DRIVER".equals(role)) {
                    // The trip selected on the login page is authoritative for this session.
                    // Do not silently fall back to the driver's database default (often MORNING).
                    String requestedTrip=str(b,"tripType").toUpperCase(Locale.ROOT);
                    if(requestedTrip.equals("EVENING")) requestedTrip="RETURN";
                    String effectiveTrip=requestedTrip;
                    if(!validTrip(effectiveTrip)){
                        json(ex,400,Map.of("message","Select MORNING or RETURN before signing in as a driver."));return;
                    }
                    s = new Session(((Number)found.get(0).get("id")).intValue(), email, role,
                        String.valueOf(found.get(0).get("full_name")), effectiveTrip);
                } else {
                    s = new Session(((Number)found.get(0).get("id")).intValue(), email, role,
                        String.valueOf(found.get(0).get("full_name")));
                }
            }
        }
        if (s == null) { json(ex,401,Map.of("message","Invalid email, password, or role.")); return; }
        String old = cookie(ex); if (old != null) SESSIONS.remove(old);
        byte[] tokenBytes = new byte[32]; RNG.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        SESSIONS.put(token,s);
        ex.getResponseHeaders().add("Set-Cookie","BUSMATE_SESSION="+token+"; Path=/; HttpOnly; SameSite=Strict");
        Map<String,Object> loginResponse=new LinkedHashMap<>();
        loginResponse.put("message","Login successful");
        loginResponse.put("role",s.role());
        loginResponse.put("name",s.name());
        loginResponse.put("email",s.email());
        if("DRIVER".equals(s.role())) loginResponse.put("tripType",s.tripType());
        json(ex,200,loginResponse);
    }

    private static void logout(HttpExchange ex) throws IOException {
        String token = cookie(ex); if (token != null) SESSIONS.remove(token);
        ex.getResponseHeaders().add("Set-Cookie","BUSMATE_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        json(ex,200,Map.of("message","Logged out"));
    }

    private static String trip(Map<String,String> q) {
        String value = q.getOrDefault("tripType", "MORNING").toUpperCase(Locale.ROOT);
        return value.equals("EVENING") ? "RETURN" : value;
    }

    private static void api(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        Map<String,String> q = query(ex.getRequestURI().getRawQuery());

        if ("OPTIONS".equals(method)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", ex.getRequestHeaders().getFirst("Origin") == null ? "*" : ex.getRequestHeaders().getFirst("Origin"));
            ex.getResponseHeaders().set("Access-Control-Allow-Methods","GET,POST,PUT,DELETE,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type");
            ex.getResponseHeaders().set("Access-Control-Allow-Credentials","true");
            ex.sendResponseHeaders(204,-1); return;
        }
        if (path.equals("/api/health")) {
            try (Connection ignored = db()) { json(ex,200,Map.of("status","OK","database","connected","databaseName","busmate_v2")); }
            return;
        }
        if (path.equals("/api/login")) { login(ex); return; }
        if (path.equals("/api/logout")) { logout(ex); return; }
        if (path.equals("/api/me")) {
            Session s=session(ex);
            if(s==null) json(ex,401,Map.of("message","Not logged in"));
            else json(ex,200,Map.of("userId",s.id(),"email",s.email(),"role",s.role(),"name",s.name()));
            return;
        }
        if (path.equals("/api/areas")) {
            json(ex,200,Map.of("items",rows("SELECT area_id AS areaId,area_name AS areaName FROM areas WHERE active=1 ORDER BY area_name")));
            return;
        }

        if (path.startsWith("/api/student/")) { studentApi(ex,path,method,q); return; }
        if (path.startsWith("/api/driver/")) { driverApi(ex,path,method,q); return; }
        if (path.startsWith("/api/admin/")) {
            if (!requireRole(ex,"ADMIN")) return;
            adminApi(ex,path.substring("/api/admin/".length()),method);
            return;
        }
        json(ex,404,Map.of("message","API endpoint not found: "+path));
    }

    private static void studentApi(HttpExchange ex, String path, String method, Map<String,String> q) throws Exception {
        if (!requireRole(ex,"STUDENT")) return;
        Session s=session(ex);
        if (path.equals("/api/student/profile") && method.equals("GET")) {
            List<Map<String,Object>> list=rows("SELECT student_id AS studentId,full_name AS fullName,roll_number AS rollNumber,email,gender FROM students WHERE student_id=? AND active=1",s.id());
            json(ex,200,list.isEmpty()?Map.of():list.get(0)); return;
        }
        if (path.equals("/api/student/buses") && method.equals("GET")) {
            int area=num(q,"areaId",0); String trip=trip(q);
            if (!validTrip(trip)) { json(ex,400,Map.of("message","Invalid trip type.")); return; }
            List<Map<String,Object>> items=rows("""
                SELECT b.bus_id AS busId,b.bus_number AS busNumber,b.bus_name AS busName,
                       b.capacity,
                       COUNT(DISTINCT CASE WHEN bk.booking_status IN ('ACTIVE','COMPLETED') THEN bk.booking_id END) AS bookedSeats,
                       GREATEST(b.capacity-COUNT(DISTINCT CASE WHEN bk.booking_status IN ('ACTIVE','COMPLETED') THEN bk.booking_id END),0) AS availableSeats,
                       GROUP_CONCAT(DISTINCT ar.area_name ORDER BY br.route_order SEPARATOR ', ') AS routeNames,
                       MIN(CASE WHEN br.area_id=? THEN TIME_FORMAT(br.pickup_time,'%H:%i') END) AS pickupTime
                FROM buses b
                JOIN bus_routes br ON br.bus_id=b.bus_id AND br.active=1 AND br.trip_type=?
                JOIN areas ar ON ar.area_id=br.area_id
                LEFT JOIN bookings bk ON bk.bus_id=b.bus_id AND bk.trip_type=br.trip_type AND bk.booking_status IN ('ACTIVE','COMPLETED')
                WHERE b.active=1 AND EXISTS(
                    SELECT 1 FROM bus_routes r WHERE r.bus_id=b.bus_id AND r.area_id=? AND r.trip_type=? AND r.active=1
                )
                GROUP BY b.bus_id,b.bus_number,b.bus_name,b.capacity
                HAVING availableSeats>0
                ORDER BY availableSeats DESC,b.bus_number
            """,area,trip,area,trip);
            json(ex,200,Map.of("items",items)); return;
        }
        if ((path.equals("/api/student/seats") ||
             path.matches("/api/student/buses/\\d+/seats")) && method.equals("GET")) {
            int bus;
            if (path.equals("/api/student/seats")) bus=num(q,"busId",0);
            else bus=Integer.parseInt(path.split("/")[4]);
            String trip=trip(q);
            if(!validTrip(trip)){json(ex,400,Map.of("message","Invalid trip type."));return;}
            List<Map<String,Object>> busRows=rows("SELECT capacity FROM buses WHERE bus_id=? AND active=1",bus);
            if(busRows.isEmpty()){json(ex,404,Map.of("message","Bus not found."));return;}
            int capacity=((Number)busRows.get(0).get("capacity")).intValue();
            List<Map<String,Object>> occupied=rows("""
                SELECT bk.seat_number AS seatNumber,st.gender
                FROM bookings bk JOIN students st ON st.student_id=bk.student_id
                WHERE bk.bus_id=? AND bk.trip_type=? AND bk.booking_status IN ('ACTIVE','COMPLETED')
            """,bus,trip);
            Map<Integer,String> occupiedGender=new HashMap<>();
            for(Map<String,Object> item:occupied)
                occupiedGender.put(((Number)item.get("seatNumber")).intValue(),String.valueOf(item.get("gender")));
            List<Map<String,Object>> seats=new ArrayList<>();
            for(int seat=1;seat<=capacity;seat++){
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("seatNumber",seat);
                boolean booked=occupiedGender.containsKey(seat);
                item.put("status",booked?"BOOKED":"AVAILABLE");
                item.put("booked",booked);
                item.put("gender",booked?occupiedGender.get(seat):null);
                seats.add(item);
            }
            json(ex,200,Map.of("capacity",capacity,"items",seats)); return;
        }
        if (path.equals("/api/student/bookings") && method.equals("GET")) {
            json(ex,200,Map.of("items",rows("""
                SELECT bk.booking_id AS bookingId,bk.bus_id AS busId,b.bus_number AS busNumber,
                       bk.trip_type AS tripType,bk.seat_number AS seatNumber,bk.area_id AS areaId,
                       a.area_name AS areaName,bk.booking_status AS bookingStatus,bk.trip_status AS tripStatus,
                       bk.booked_at AS bookedAt,bk.otp_expires_at AS otpExpiresAt,bk.otp_code AS otp
                FROM bookings bk JOIN buses b ON b.bus_id=bk.bus_id
                JOIN areas a ON a.area_id=bk.area_id
                WHERE bk.student_id=? AND bk.booking_status IN ('ACTIVE','COMPLETED')
                ORDER BY bk.booked_at DESC
            """,s.id()))); return;
        }
        if (path.equals("/api/student/bookings") && method.equals("POST")) {
            Map<String,Object> b=body(ex);
            int bus=num(b,"busId",0), area=num(b,"areaId",0), requestedSeat=num(b,"seatNumber",0);
            String trip=str(b,"tripType").toUpperCase(Locale.ROOT);
            if(trip.equals("EVENING"))trip="RETURN";
            if(!validTrip(trip)||bus<=0||area<=0||requestedSeat<=0){json(ex,400,Map.of("message","Select a valid trip, area, bus and seat."));return;}
            createBooking(ex,s,bus,area,trip,requestedSeat); return;
        }
        if (path.matches("/api/student/bookings/\\d+") && method.equals("DELETE")) {
            long id=Long.parseLong(path.substring(path.lastIndexOf('/')+1));
            int count=update("""
                UPDATE bookings SET booking_status='CANCELLED',trip_status='CANCELLED',cancelled_at=NOW()
                WHERE booking_id=? AND student_id=? AND booking_status='ACTIVE' AND trip_status='BOOKED'
                  AND booked_at >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)
            """,id,s.id());
            if(count==0){json(ex,400,Map.of("message","Booking cannot be cancelled. The 5-minute cancellation period may have expired, or the booking is no longer active."));return;}
            json(ex,200,Map.of("message","Booking cancelled and seat released.")); return;
        }
        if (path.equals("/api/student/requests") && method.equals("POST")) {
            Map<String,Object>b=body(ex); int area=num(b,"areaId",0);
            String trip=str(b,"tripType").toUpperCase(Locale.ROOT); if(trip.equals("EVENING"))trip="RETURN";
            if(!validTrip(trip)||rows("SELECT area_id FROM areas WHERE area_id=? AND active=1",area).isEmpty()){
                json(ex,400,Map.of("message","Choose a valid area and trip."));return;
            }
            int id=insert("INSERT INTO bus_requests(student_id,area_id,trip_type,request_note,request_status) VALUES(?,?,?,?,'PENDING')",
                s.id(),area,trip,str(b,"reason").isBlank()?str(b,"requestNote"):str(b,"reason"));
            json(ex,201,Map.of("message","Bus request submitted.","requestId",id));return;
        }
        if (path.equals("/api/student/requests") && method.equals("GET")) {
            json(ex,200,Map.of("items",rows("""
                SELECT br.request_id AS requestId,br.area_id AS areaId,a.area_name AS areaName,
                       br.trip_type AS tripType,br.request_note AS reason,br.request_status AS requestStatus,
                       br.admin_message AS adminMessage,br.created_at AS createdAt,br.reviewed_at AS reviewedAt
                FROM bus_requests br JOIN areas a ON a.area_id=br.area_id
                WHERE br.student_id=? ORDER BY br.created_at DESC
            """,s.id())));return;
        }
        json(ex,404,Map.of("message","Unknown student endpoint."));
    }

    private static void createBooking(HttpExchange ex, Session student, int busId, int areaId, String trip, int requestedSeat) throws Exception {
        try(Connection c=db()){
            c.setAutoCommit(false);
            try{
                int capacity;
                try(PreparedStatement p=c.prepareStatement("SELECT capacity FROM buses WHERE bus_id=? AND active=1 FOR UPDATE")){
                    p.setInt(1,busId);try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("Bus is not available.");capacity=r.getInt(1);}
                }
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM bus_routes WHERE bus_id=? AND area_id=? AND trip_type=? AND active=1")){
                    p.setInt(1,busId);p.setInt(2,areaId);p.setString(3,trip);
                    try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("This bus is not allocated to the selected area and trip.");}
                }
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM bookings WHERE student_id=? AND booking_status IN ('ACTIVE','COMPLETED')")){
                    p.setInt(1,student.id());try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalArgumentException("Your previous trip is not cleared yet. You can book another trip only after the assigned driver uses Clear this trip's data.");}
                }
                int seat=requestedSeat;
                if(seat<1||seat>capacity)throw new IllegalArgumentException("Choose a valid seat number.");
                try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM bookings WHERE bus_id=? AND trip_type=? AND seat_number=? AND booking_status='ACTIVE'")){
                    p.setInt(1,busId);p.setString(2,trip);p.setInt(3,seat);
                    try(ResultSet r=p.executeQuery()){if(r.next())throw new IllegalArgumentException("This seat has just been booked. Please choose another seat.");}
                }
                String gender;
                try(PreparedStatement p=c.prepareStatement("SELECT gender FROM students WHERE student_id=? AND active=1")){
                    p.setInt(1,student.id());try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("Student account is inactive.");gender=r.getString(1);}
                }
                // Physical seat adjacency is evaluated by consecutive seat numbers in this default 1-D layout.
                if(!"OTHER".equalsIgnoreCase(gender)){
                    try(PreparedStatement p=c.prepareStatement("""
                        SELECT st.gender FROM bookings bk JOIN students st ON st.student_id=bk.student_id
                        WHERE bk.bus_id=? AND bk.trip_type=? AND bk.booking_status IN ('ACTIVE','COMPLETED')
                          AND bk.seat_number IN (?,?)
                    """)){
                        p.setInt(1,busId);p.setString(2,trip);p.setInt(3,seat-1);p.setInt(4,seat+1);
                        try(ResultSet r=p.executeQuery()){while(r.next()){
                            String other=r.getString(1);
                            if(!"OTHER".equalsIgnoreCase(other)&&!gender.equalsIgnoreCase(other))
                                throw new IllegalArgumentException("This seat is next to a passenger of a different gender. Choose another seat.");
                        }}
                    }
                }
                String otp=String.format("%04d",1000+RNG.nextInt(9000));
                String hash=sha256(otp);
                try(PreparedStatement p=c.prepareStatement("""
                    INSERT INTO bookings(student_id,bus_id,trip_type,seat_number,area_id,booking_status,trip_status,otp_hash,otp_code,otp_expires_at)
                    VALUES(?,?,?,?,?,'ACTIVE','BOOKED',?,?,DATE_ADD(NOW(),INTERVAL 1 DAY))
                """)){
                    p.setInt(1,student.id());p.setInt(2,busId);p.setString(3,trip);p.setInt(4,seat);p.setInt(5,areaId);p.setString(6,hash);p.setString(7,otp);p.executeUpdate();
                }
                c.commit();
                json(ex,201,Map.of("message","Seat booked successfully.","seatNumber",seat,"otp",otp,"tripType",trip));
            }catch(Exception e){c.rollback();throw e;}
        }
    }

    private static void driverApi(HttpExchange ex,String path,String method,Map<String,String> q)throws Exception{
        if(!requireRole(ex,"DRIVER"))return;
        Session s=session(ex);
        if(path.equals("/api/driver/profile")&&method.equals("GET")){
            List<Map<String,Object>> a=rows("""
                SELECT driver_id AS driverId,driver_code AS driverCode,full_name AS fullName,email,
                       gender,bus_id AS busId,trip_type AS tripType
                FROM drivers WHERE driver_id=? AND active=1
            """,s.id());
            Map<String,Object> profile=a.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(a.get(0));
            profile.put("tripType",s.tripType()==null?profile.get("tripType"):s.tripType());
            json(ex,200,profile);return;
        }
        if(path.equals("/api/driver/bus")&&method.equals("GET")){
            List<Map<String,Object>> a=rows("""
                SELECT b.bus_id AS busId,b.bus_number AS busNumber,b.bus_name AS busName,b.capacity,
                       ? AS tripType,
                       COUNT(DISTINCT CASE WHEN bk.booking_status IN ('ACTIVE','COMPLETED') THEN bk.booking_id END) AS bookedSeats,
                       GREATEST(b.capacity-COUNT(DISTINCT CASE WHEN bk.booking_status IN ('ACTIVE','COMPLETED') THEN bk.booking_id END),0) AS availableSeats,
                       GROUP_CONCAT(DISTINCT ar.area_name ORDER BY br.route_order SEPARATOR ', ') AS routeNames,
                       GROUP_CONCAT(DISTINCT TIME_FORMAT(br.pickup_time,'%H:%i') ORDER BY br.route_order SEPARATOR ', ') AS pickupTimes
                FROM drivers d LEFT JOIN buses b ON b.bus_id=d.bus_id
                LEFT JOIN bus_routes br ON br.bus_id=b.bus_id AND br.trip_type=? AND br.active=1
                LEFT JOIN areas ar ON ar.area_id=br.area_id
                LEFT JOIN bookings bk ON bk.bus_id=b.bus_id AND bk.trip_type=? AND bk.booking_status IN ('ACTIVE','COMPLETED')
                WHERE d.driver_id=? GROUP BY b.bus_id,b.bus_number,b.bus_name,b.capacity
            """,s.tripType(),s.tripType(),s.tripType(),s.id());
            json(ex,200,Map.of("bus",a.isEmpty()?Map.of():a.get(0)));return;
        }
        if(path.equals("/api/driver/passengers")&&method.equals("GET")){
            json(ex,200,Map.of("items",rows("""
                SELECT st.full_name AS fullName,st.roll_number AS rollNumber,st.email,st.gender,
                       bk.booking_id AS bookingId,bk.seat_number AS seatNumber,bk.trip_type AS tripType,
                       bk.booking_status AS bookingStatus,bk.trip_status AS tripStatus,a.area_name AS areaName
                FROM drivers d JOIN bookings bk ON bk.bus_id=d.bus_id
                JOIN students st ON st.student_id=bk.student_id
                JOIN areas a ON a.area_id=bk.area_id
                WHERE d.driver_id=? AND d.active=1 AND bk.trip_type=?
                  AND bk.booking_status IN ('ACTIVE','COMPLETED')
                ORDER BY bk.seat_number,bk.booked_at
            """,s.id(),s.tripType())));return;
        }
        if(path.equals("/api/driver/clear-data")&&method.equals("POST")){
            Map<String,Object> request=body(ex);
            if(!Boolean.TRUE.equals(request.get("confirm"))){
                json(ex,400,Map.of("message","Explicit confirmation is required."));return;
            }
            int removed=clearDriverTripData(s.id(),s.tripType());
            json(ex,200,Map.of("message","Cleared "+removed+" booking record(s) for your assigned bus and trip only.","deletedBookings",removed));return;
        }
        if(path.equals("/api/driver/verify-otp")&&method.equals("POST")){
            Map<String,Object>b=body(ex);String otp=str(b,"otp");
            if(!otp.matches("\\d{4}")){json(ex,400,Map.of("message","Enter a valid 4-digit OTP."));return;}
            verifyOtp(ex,s,otp);return;
        }
        json(ex,404,Map.of("message","Unknown driver endpoint."));
    }

    private static int clearDriverTripData(int driverId,String tripType) throws SQLException {
        try(Connection c=db()){
            c.setAutoCommit(false);
            try{
                int busId;
                try(PreparedStatement p=c.prepareStatement(
                    "SELECT bus_id FROM drivers WHERE driver_id=? AND active=1 FOR UPDATE")){
                    p.setInt(1,driverId);
                    try(ResultSet r=p.executeQuery()){
                        if(!r.next())throw new SQLException("Active driver assignment was not found.");
                        busId=r.getInt("bus_id");
                        if(r.wasNull())throw new SQLException("No bus is assigned to this driver.");
                    }
                }
                // Remove notification history first, then booking rows, scoped to this driver's bus + trip.
                try(PreparedStatement p=c.prepareStatement("""
                    DELETE nl FROM notification_logs nl
                    JOIN bookings bk ON bk.booking_id=nl.booking_id
                    WHERE bk.bus_id=? AND bk.trip_type=?
                """)){
                    p.setInt(1,busId);p.setString(2,tripType);p.executeUpdate();
                }
                int deleted;
                try(PreparedStatement p=c.prepareStatement(
                    "DELETE FROM bookings WHERE bus_id=? AND trip_type=?")){
                    p.setInt(1,busId);p.setString(2,tripType);deleted=p.executeUpdate();
                }
                c.commit();
                return deleted;
            }catch(Exception e){
                c.rollback();
                if(e instanceof SQLException se)throw se;
                throw new SQLException("Unable to clear this trip's records.",e);
            }
        }
    }

    private static void verifyOtp(HttpExchange ex,Session driver,String otp)throws Exception{
        String hash=sha256(otp);
        Map<String,Object> booking;
        try(Connection c=db()){
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("""
                SELECT bk.booking_id,bk.student_id,bk.trip_type,bk.seat_number,bk.otp_expires_at,
                       st.full_name,st.roll_number,st.email,b.bus_number,a.area_name
                FROM drivers d
                JOIN bookings bk ON bk.bus_id=d.bus_id AND bk.trip_type=?
                JOIN students st ON st.student_id=bk.student_id
                JOIN buses b ON b.bus_id=bk.bus_id
                JOIN areas a ON a.area_id=bk.area_id
                WHERE d.driver_id=? AND d.active=1 AND bk.booking_status='ACTIVE'
                  AND bk.trip_status='BOOKED' AND bk.otp_hash=? AND bk.otp_expires_at>NOW()
                FOR UPDATE
            """)){
                p.setString(1,driver.tripType());p.setInt(2,driver.id());p.setString(3,hash);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next())throw new IllegalArgumentException("Invalid, expired, already used, or unassigned OTP.");
                    booking=new LinkedHashMap<>();
                    booking.put("bookingId",r.getLong("booking_id"));booking.put("studentId",r.getInt("student_id"));
                    booking.put("tripType",r.getString("trip_type"));booking.put("seatNumber",r.getInt("seat_number"));
                    booking.put("studentName",r.getString("full_name"));booking.put("rollNumber",r.getString("roll_number"));
                    booking.put("studentEmail",r.getString("email"));booking.put("busNumber",r.getString("bus_number"));
                    booking.put("areaName",r.getString("area_name"));
                }
            }
            String trip=String.valueOf(booking.get("tripType"));
            String newStatus="MORNING".equals(trip)?"BOARDED":"DROPPED";
            try(PreparedStatement p=c.prepareStatement("""
                UPDATE bookings SET booking_status='COMPLETED',trip_status=?,otp_verified_at=NOW(),
                    otp_hash=NULL,otp_code=NULL,otp_expires_at=NULL
                WHERE booking_id=? AND booking_status='ACTIVE' AND trip_status='BOOKED' 
            """)){
                p.setString(1,newStatus);p.setLong(2,((Number)booking.get("bookingId")).longValue());
                if(p.executeUpdate()!=1)throw new IllegalArgumentException("This OTP has already been used.");
            }
            c.commit();
            booking.put("tripStatus",newStatus);
        }
        Map<String,Object> notify=sendTelegram(booking);
        try{
            insert("""
                INSERT INTO notification_logs(booking_id,student_id,notification_type,channel,recipient,message,delivery_status,external_message_id,error_message,sent_at)
                VALUES(?,?,?,'TELEGRAM',?,?,?,?,?,?)
            """,booking.get("bookingId"),booking.get("studentId"),booking.get("tripType").equals("MORNING")?"BOARDED":"DROPPED",
                TELEGRAM_CHAT_ID==null?"":TELEGRAM_CHAT_ID,notify.getOrDefault("message",""),notify.get("status"),
                notify.get("messageId"),notify.get("error"),"SENT".equals(notify.get("status"))?java.sql.Timestamp.valueOf(LocalDateTime.now()):null);
        }catch(Exception logError){logError.printStackTrace();}
        Map<String,Object> response=new LinkedHashMap<>(booking);
        response.put("notificationStatus",notify.get("status"));
        response.put("notificationMessage",notify.get("message"));
        json(ex,200,response);
    }

    private static Map<String,Object> sendTelegram(Map<String,Object> booking){
        Map<String,Object> result=new LinkedHashMap<>();
        if(TELEGRAM_TOKEN==null||TELEGRAM_TOKEN.isBlank()||TELEGRAM_CHAT_ID==null||TELEGRAM_CHAT_ID.isBlank()){
            result.put("status","FAILED");result.put("message","Telegram is not configured. Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID.");result.put("error","Missing Telegram environment variables.");return result;
        }
        String status=String.valueOf(booking.get("tripStatus"));
        String message="BusMate "+status+" verification\n"+
            "Student: "+booking.get("studentName")+"\n"+
            "Roll No: "+booking.get("rollNumber")+"\n"+
            "Bus: "+booking.get("busNumber")+"\n"+
            "Trip: "+booking.get("tripType")+"\n"+
            "Seat: "+booking.get("seatNumber")+"\n"+
            "Area: "+booking.get("areaName");
        try{
            String form="chat_id="+URLEncoder.encode(TELEGRAM_CHAT_ID,StandardCharsets.UTF_8)+
                "&text="+URLEncoder.encode(message,StandardCharsets.UTF_8);
            HttpRequest request=HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot"+TELEGRAM_TOKEN+"/sendMessage"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
            JsonObject root=JsonParser.parseString(response.body()).getAsJsonObject();
            if(response.statusCode()==200&&root.has("ok")&&root.get("ok").getAsBoolean()){
                result.put("status","SENT");result.put("message","Telegram notification sent.");
                result.put("messageId",root.getAsJsonObject("result").get("message_id").getAsString());
            }else{
                result.put("status","FAILED");result.put("message","OTP verified, but Telegram delivery failed.");
                result.put("error",response.body());
            }
        }catch(Exception e){
            result.put("status","FAILED");result.put("message","OTP verified, but Telegram delivery failed.");
            result.put("error",e.getMessage()==null?"Telegram request failed":e.getMessage());
        }
        return result;
    }

    private static void adminApi(HttpExchange ex,String sub,String method)throws Exception{
        if(sub.equals("summary")){
            json(ex,200,Map.of(
                "students",scalar("SELECT COUNT(*) FROM students"),
                "drivers",scalar("SELECT COUNT(*) FROM drivers"),
                "buses",scalar("SELECT COUNT(*) FROM buses"),
                "pendingRequests",scalar("SELECT COUNT(*) FROM bus_requests WHERE request_status='PENDING'")
            ));return;
        }
        if(sub.equals("students")){
            if(method.equals("GET")){json(ex,200,Map.of("items",rows("SELECT student_id AS studentId,full_name AS fullName,roll_number AS rollNumber,email,gender,active FROM students ORDER BY full_name")));return;}
            if(method.equals("POST")){
                Map<String,Object>b=body(ex);String pw=str(b,"password");if(pw.isBlank())pw="123@RC";
                insert("INSERT INTO students(full_name,roll_number,email,password_hash,gender,active) VALUES(?,?,?,?,?,1)",
                    str(b,"fullName"),str(b,"rollNumber"),str(b,"email").toLowerCase(Locale.ROOT),sha256(pw),gender(str(b,"gender")));
                json(ex,201,Map.of("message","Student created."));return;
            }
        }
        if(sub.startsWith("students/")){
            int id=tailId(sub);Map<String,Object>b=body(ex);
            if(b.containsKey("active"))update("UPDATE students SET active=? WHERE student_id=?",bool(b,"active"),id);
            else if(!str(b,"password").isBlank())update("UPDATE students SET full_name=?,roll_number=?,email=?,gender=?,password_hash=? WHERE student_id=?",
                str(b,"fullName"),str(b,"rollNumber"),str(b,"email").toLowerCase(Locale.ROOT),gender(str(b,"gender")),sha256(str(b,"password")),id);
            else update("UPDATE students SET full_name=?,roll_number=?,email=?,gender=? WHERE student_id=?",
                str(b,"fullName"),str(b,"rollNumber"),str(b,"email").toLowerCase(Locale.ROOT),gender(str(b,"gender")),id);
            json(ex,200,Map.of("message","Student updated."));return;
        }
        if(sub.equals("drivers")){
            if(method.equals("GET")){json(ex,200,Map.of("items",rows("""
                SELECT d.driver_id AS driverId,d.full_name AS fullName,d.driver_code AS driverCode,d.email,
                       d.gender,d.bus_id AS busId,b.bus_number AS busNumber,d.trip_type AS tripType,d.active
                FROM drivers d LEFT JOIN buses b ON b.bus_id=d.bus_id ORDER BY d.full_name
            """)));return;}
            if(method.equals("POST")){
                Map<String,Object>b=body(ex);String pw=str(b,"password");if(pw.isBlank())pw="123@RC";
                insert("INSERT INTO drivers(full_name,driver_code,email,password_hash,gender,bus_id,trip_type,active) VALUES(?,?,?,?,?,?,?,1)",
                    str(b,"fullName"),str(b,"driverCode"),str(b,"email").toLowerCase(Locale.ROOT),sha256(pw),
                    gender(str(b,"gender")),nullableInt(b,"busId"),normalizeTrip(str(b,"tripType")));
                json(ex,201,Map.of("message","Driver created."));return;
            }
        }
        if(sub.startsWith("drivers/")){
            int id=tailId(sub);Map<String,Object>b=body(ex);
            if(b.containsKey("active"))update("UPDATE drivers SET active=? WHERE driver_id=?",bool(b,"active"),id);
            else if(!str(b,"password").isBlank())update("UPDATE drivers SET full_name=?,driver_code=?,email=?,gender=?,bus_id=?,trip_type=?,password_hash=? WHERE driver_id=?",
                str(b,"fullName"),str(b,"driverCode"),str(b,"email").toLowerCase(Locale.ROOT),gender(str(b,"gender")),nullableInt(b,"busId"),
                normalizeTrip(str(b,"tripType")),sha256(str(b,"password")),id);
            else update("UPDATE drivers SET full_name=?,driver_code=?,email=?,gender=?,bus_id=?,trip_type=? WHERE driver_id=?",
                str(b,"fullName"),str(b,"driverCode"),str(b,"email").toLowerCase(Locale.ROOT),gender(str(b,"gender")),nullableInt(b,"busId"),
                normalizeTrip(str(b,"tripType")),id);
            json(ex,200,Map.of("message","Driver updated."));return;
        }
        if(sub.equals("buses")){
            if(method.equals("GET")){json(ex,200,Map.of("items",rows("SELECT bus_id AS busId,bus_number AS busNumber,bus_name AS busName,registration_number AS registrationNumber,capacity,active FROM buses ORDER BY bus_number")));return;}
            if(method.equals("POST")){Map<String,Object>b=body(ex);insert("INSERT INTO buses(bus_number,bus_name,registration_number,capacity,active) VALUES(?,?,?,?,1)",
                str(b,"busNumber"),str(b,"busName"),emptyNull(str(b,"registrationNumber")),num(b,"capacity",40));
                json(ex,201,Map.of("message","Bus created."));return;}
        }
        if(sub.startsWith("buses/")){
            int id=tailId(sub);Map<String,Object>b=body(ex);
            if(b.containsKey("active"))update("UPDATE buses SET active=? WHERE bus_id=?",bool(b,"active"),id);
            else update("UPDATE buses SET bus_number=?,bus_name=?,registration_number=?,capacity=? WHERE bus_id=?",
                str(b,"busNumber"),str(b,"busName"),emptyNull(str(b,"registrationNumber")),num(b,"capacity",40),id);
            json(ex,200,Map.of("message","Bus updated."));return;
        }
        if(sub.equals("areas")){
            if(method.equals("GET")){
                json(ex,200,Map.of("items",rows("SELECT area_id AS areaId,area_name AS areaName,active FROM areas ORDER BY area_id")));
                return;
            }
            if(method.equals("POST")){
                Map<String,Object>b=body(ex);
                String name=str(b,"areaName").trim();
                if(name.isBlank()){json(ex,400,Map.of("message","Area name is required."));return;}
                insert("INSERT INTO areas(area_name,active) VALUES(?,1)",name);
                json(ex,201,Map.of("message","Area created."));return;
            }
        }
        if(sub.startsWith("areas/")){
            int id=tailId(sub);Map<String,Object>b=body(ex);
            if(b.containsKey("active")){
                update("UPDATE areas SET active=? WHERE area_id=?",bool(b,"active"),id);
            }else{
                String name=str(b,"areaName").trim();
                if(name.isBlank()){json(ex,400,Map.of("message","Area name is required."));return;}
                update("UPDATE areas SET area_name=? WHERE area_id=?",name,id);
            }
            json(ex,200,Map.of("message","Area updated."));return;
        }
        if(sub.equals("routes")){
            if(method.equals("GET")){json(ex,200,Map.of("items",rows("""
                SELECT r.route_id AS routeId,r.bus_id AS busId,r.area_id AS areaId,b.bus_number AS busNumber,
                       a.area_name AS areaName,TIME_FORMAT(r.pickup_time,'%H:%i') AS pickupTime,
                       r.route_order AS routeOrder,r.trip_type AS tripType,r.active
                FROM bus_routes r JOIN buses b ON b.bus_id=r.bus_id JOIN areas a ON a.area_id=r.area_id
                ORDER BY b.bus_number,r.trip_type,r.route_order
            """)));return;}
            if(method.equals("POST")){Map<String,Object>b=body(ex);insert("INSERT INTO bus_routes(bus_id,area_id,trip_type,pickup_time,route_order,active) VALUES(?,?,?,?,?,1)",
                num(b,"busId",0),num(b,"areaId",0),normalizeTrip(str(b,"tripType")),emptyNull(str(b,"pickupTime")),num(b,"routeOrder",1));
                json(ex,201,Map.of("message","Route created."));return;}
        }
        if(sub.startsWith("routes/")){
            int id=tailId(sub);Map<String,Object>b=body(ex);
            update("UPDATE bus_routes SET bus_id=?,area_id=?,trip_type=?,pickup_time=?,route_order=? WHERE route_id=?",
                num(b,"busId",0),num(b,"areaId",0),normalizeTrip(str(b,"tripType")),emptyNull(str(b,"pickupTime")),num(b,"routeOrder",1),id);
            json(ex,200,Map.of("message","Route updated."));return;
        }
        if(sub.equals("trips")){
            if(method.equals("GET")){json(ex,200,Map.of("items",rows("""
                SELECT tc.trip_id AS tripId,tc.trip_type AS tripType,tc.bus_id AS busId,
                       b.bus_number AS busNumber,tc.driver_id AS driverId,d.full_name AS driverName,
                       tc.start_area_id AS startAreaId,tc.destination_area_id AS destinationAreaId,
                       tc.start_time AS startTime,tc.description,tc.active
                FROM trip_configurations tc
                LEFT JOIN buses b ON b.bus_id=tc.bus_id
                LEFT JOIN drivers d ON d.driver_id=tc.driver_id
                ORDER BY tc.trip_type
            """)));return;}
            if(method.equals("PUT")){
                Map<String,Object>b=body(ex);String t=normalizeTrip(str(b,"tripType"));
                update("""
                    UPDATE trip_configurations SET bus_id=?,driver_id=?,start_area_id=?,destination_area_id=?,
                    start_time=?,description=?,active=? WHERE trip_type=?
                """,nullableInt(b,"busId"),nullableInt(b,"driverId"),nullableInt(b,"startAreaId"),
                    nullableInt(b,"destinationAreaId"),emptyNull(str(b,"startTime")),emptyNull(str(b,"description")),
                    b.containsKey("active")?bool(b,"active"):true,t);
                json(ex,200,Map.of("message","Trip configuration updated."));return;
            }
        }
        if(sub.equals("requests")&&method.equals("GET")){
            json(ex,200,Map.of("items",rows("""
                SELECT br.request_id AS requestId,br.student_id AS studentId,s.full_name AS studentName,
                       s.email AS studentEmail,s.roll_number AS rollNumber,br.area_id AS areaId,a.area_name AS areaName,
                       br.trip_type AS tripType,br.request_note AS reason,br.request_status AS requestStatus,
                       br.admin_message AS adminMessage,br.created_at AS createdAt,br.reviewed_at AS reviewedAt
                FROM bus_requests br JOIN areas a ON a.area_id=br.area_id JOIN students s ON s.student_id=br.student_id
                ORDER BY (br.request_status='PENDING') DESC,br.created_at DESC
            """)));return;
        }
        if(sub.startsWith("requests/")&&method.equals("PUT")){
            int id=tailId(sub);Map<String,Object>b=body(ex);String status=str(b,"requestStatus").toUpperCase(Locale.ROOT);
            if(!Set.of("APPROVED","REJECTED").contains(status)){json(ex,400,Map.of("message","Choose APPROVED or REJECTED."));return;}
            Session admin=session(ex);
            int changed=update("UPDATE bus_requests SET request_status=?,admin_message=?,reviewed_by_admin_id=?,reviewed_at=NOW() WHERE request_id=?",
                status,emptyNull(str(b,"adminMessage")),admin.id(),id);
            if(changed==0){json(ex,404,Map.of("message","Request not found."));return;}
            json(ex,200,Map.of("message","Request updated and response saved for the student."));return;
        }
        json(ex,404,Map.of("message","Unknown admin endpoint."));
    }

    private static Object scalar(String sql)throws SQLException{
        List<Map<String,Object>> r=rows(sql);return r.isEmpty()?0:r.get(0).values().iterator().next();
    }
    private static boolean validTrip(String t){return "MORNING".equals(t)||"RETURN".equals(t);}
    private static String normalizeTrip(String t){
        String v=t.toUpperCase(Locale.ROOT);if(v.equals("EVENING"))v="RETURN";
        if(!validTrip(v))throw new IllegalArgumentException("Trip type must be MORNING or RETURN.");
        return v;
    }
    private static String gender(String g){
        String v=g.toUpperCase(Locale.ROOT);if(!Set.of("MALE","FEMALE","OTHER").contains(v))return "OTHER";return v;
    }
    private static Object emptyNull(String s){return s==null||s.isBlank()?null:s;}
    private static Object nullableInt(Map<String,Object>b,String key){
        String v=str(b,key);if(v.isBlank()||v.equals("0"))return null;
        try{return Integer.parseInt(v);}catch(Exception e){throw new IllegalArgumentException("Invalid "+key+".");}
    }
    private static int tailId(String path){try{return Integer.parseInt(path.substring(path.lastIndexOf('/')+1));}catch(Exception e){throw new IllegalArgumentException("Invalid record ID.");}}

    private static void serveStatic(HttpExchange ex)throws IOException{
        String path=ex.getRequestURI().getPath();
        if(path.equals("/"))path="/home.html";
        java.nio.file.Path root=java.nio.file.Paths.get("").toAbsolutePath().normalize();
        java.nio.file.Path file=root.resolve(path.substring(1)).normalize();
        if(!file.startsWith(root)||!java.nio.file.Files.isRegularFile(file)){
            byte[] b="Not found".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(404,b.length);
            try(OutputStream out=ex.getResponseBody()){out.write(b);}return;
        }
        String name=file.toString().toLowerCase(Locale.ROOT);
        String type=name.endsWith(".html")?"text/html; charset=UTF-8":name.endsWith(".css")?"text/css; charset=UTF-8":
            name.endsWith(".js")?"application/javascript; charset=UTF-8":name.endsWith(".png")?"image/png":
            name.endsWith(".jpg")||name.endsWith(".jpeg")?"image/jpeg":name.endsWith(".svg")?"image/svg+xml":"application/octet-stream";
        ex.getResponseHeaders().set("Content-Type",type);ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        byte[] data=java.nio.file.Files.readAllBytes(file);ex.sendResponseHeaders(200,data.length);
        try(OutputStream out=ex.getResponseBody()){out.write(data);}
    }

    public static void main(String[] args)throws Exception{
        Class.forName("com.mysql.cj.jdbc.Driver");
        try(Connection c=db();Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM students")){
            rs.next();System.out.println("MySQL connected to busmate_v2. Students: "+rs.getInt(1));
        }
        int port=Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
        HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),0);
        server.createContext("/api/",ex->{try{api(ex);}catch(IllegalArgumentException e){try{json(ex,400,Map.of("message",e.getMessage()==null?"Invalid request":e.getMessage()));}catch(Exception ignored){}}
            catch(SQLException e){e.printStackTrace();try{json(ex,500,Map.of("message","Database error: "+e.getMessage()));}catch(Exception ignored){}}
            catch(Exception e){e.printStackTrace();try{json(ex,500,Map.of("message","Server error: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())));}catch(Exception ignored){}}
            finally{ex.close();}});
        server.createContext("/",ex->{try{serveStatic(ex);}catch(Exception e){e.printStackTrace();try{json(ex,500,Map.of("message","Could not serve file."));}catch(Exception ignored){}}finally{ex.close();}});
        server.setExecutor(Executors.newFixedThreadPool(12));server.start();
        System.out.println("BusMate started on port "+port);
        System.out.println("Health check: http://localhost:"+port+"/api/health");
    }
}
