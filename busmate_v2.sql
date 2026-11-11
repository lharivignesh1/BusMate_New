/* =========================================================
   BUSMATE V2 - COMPLETE DATABASE
   MySQL 8.0+
   Database: busmate_v2

   Includes:
   Students, Drivers, Admins, Buses, Areas,
   Morning/Return Trips, Routes, Bookings,
   OTP Verification, Boarding/Drop-off,
   Bus Requests and Notification Logs.
   ========================================================= */

CREATE DATABASE IF NOT EXISTS busmate_v2
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE busmate_v2;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS notification_logs;
DROP TABLE IF EXISTS bus_requests;
DROP TABLE IF EXISTS bookings;
DROP TABLE IF EXISTS bus_routes;
DROP TABLE IF EXISTS trip_configurations;
DROP TABLE IF EXISTS drivers;
DROP TABLE IF EXISTS buses;
DROP TABLE IF EXISTS areas;
DROP TABLE IF EXISTS students;
DROP TABLE IF EXISTS admins;

SET FOREIGN_KEY_CHECKS = 1;


/* =========================================================
   1. ADMINS
   ========================================================= */

CREATE TABLE admins (
    admin_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(190) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;


/* =========================================================
   2. STUDENTS
   ========================================================= */

CREATE TABLE students (
    student_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    full_name VARCHAR(120) NOT NULL,
    roll_number VARCHAR(40) NOT NULL UNIQUE,
    email VARCHAR(190) NOT NULL UNIQUE,

    password_hash VARCHAR(255) NOT NULL,

    gender ENUM('MALE','FEMALE','OTHER')
        NOT NULL DEFAULT 'OTHER',

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_student_name(full_name),
    INDEX idx_student_active(active)
) ENGINE=InnoDB;


/* =========================================================
   3. AREAS
   ========================================================= */

CREATE TABLE areas (
    area_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    area_name VARCHAR(100) NOT NULL UNIQUE,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;


/* =========================================================
   4. BUSES
   ========================================================= */

CREATE TABLE buses (
    bus_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    bus_number VARCHAR(20) NOT NULL UNIQUE,

    bus_name VARCHAR(100) NOT NULL,

    registration_number VARCHAR(40) UNIQUE,

    capacity INT UNSIGNED NOT NULL DEFAULT 40,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT chk_bus_capacity
        CHECK (capacity BETWEEN 1 AND 100)
) ENGINE=InnoDB;


/* =========================================================
   5. DRIVERS
   One driver is assigned to one bus and one trip.
   ========================================================= */

CREATE TABLE drivers (
    driver_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    driver_code VARCHAR(40) NOT NULL UNIQUE,

    full_name VARCHAR(120) NOT NULL,

    email VARCHAR(190) NOT NULL UNIQUE,

    password_hash VARCHAR(255) NOT NULL,

    gender ENUM('MALE','FEMALE','OTHER')
        NOT NULL DEFAULT 'OTHER',

    bus_id INT UNSIGNED NULL,

    trip_type ENUM('MORNING','RETURN') NOT NULL DEFAULT 'MORNING',

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_driver_bus_trip(bus_id,trip_type),

    CONSTRAINT fk_driver_bus
        FOREIGN KEY(bus_id)
        REFERENCES buses(bus_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;


/* =========================================================
   6. TRIP CONFIGURATIONS
   Separate Morning and Return configuration.
   ========================================================= */

CREATE TABLE trip_configurations (

    trip_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    trip_type ENUM('MORNING','RETURN') NOT NULL UNIQUE,

    bus_id INT UNSIGNED NULL,

    driver_id INT UNSIGNED NULL,

    start_area_id INT UNSIGNED NULL,

    destination_area_id INT UNSIGNED NULL,

    start_time TIME NULL,

    description VARCHAR(255),

    active BOOLEAN NOT NULL DEFAULT TRUE,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_trip_bus
        FOREIGN KEY(bus_id)
        REFERENCES buses(bus_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_trip_driver
        FOREIGN KEY(driver_id)
        REFERENCES drivers(driver_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_trip_start_area
        FOREIGN KEY(start_area_id)
        REFERENCES areas(area_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_trip_destination_area
        FOREIGN KEY(destination_area_id)
        REFERENCES areas(area_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;


/* =========================================================
   7. BUS ROUTES
   Each bus can have different Morning and Return routes.
   ========================================================= */

CREATE TABLE bus_routes (

    route_id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    bus_id INT UNSIGNED NOT NULL,

    area_id INT UNSIGNED NOT NULL,

    trip_type ENUM('MORNING','RETURN') NOT NULL,

    pickup_time TIME NULL,

    route_order TINYINT UNSIGNED NOT NULL DEFAULT 1,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    UNIQUE KEY uq_bus_area_trip(
        bus_id,
        area_id,
        trip_type
    ),

    INDEX idx_route_area_trip(
        area_id,
        trip_type,
        active
    ),

    CONSTRAINT fk_route_bus
        FOREIGN KEY(bus_id)
        REFERENCES buses(bus_id),

    CONSTRAINT fk_route_area
        FOREIGN KEY(area_id)
        REFERENCES areas(area_id)
) ENGINE=InnoDB;


/* =========================================================
   8. BOOKINGS
   One active booking per student.
   One active student per bus seat per trip.
   ========================================================= */

CREATE TABLE bookings (

    booking_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    student_id INT UNSIGNED NOT NULL,

    bus_id INT UNSIGNED NOT NULL,

    trip_type ENUM('MORNING','RETURN') NOT NULL,

    seat_number TINYINT UNSIGNED NOT NULL,

    area_id INT UNSIGNED NOT NULL,

    booking_status ENUM(
        'ACTIVE',
        'CANCELLED'
    ) NOT NULL DEFAULT 'ACTIVE',

    trip_status ENUM(
        'BOOKED',
        'BOARDED',
        'DROPPED',
        'CANCELLED'
    ) NOT NULL DEFAULT 'BOOKED',

    otp_hash CHAR(64) NULL,

    otp_expires_at DATETIME NULL,

    otp_verified_at DATETIME NULL,

    booked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    cancelled_at DATETIME NULL,

    /*
      Generated columns ensure cancelled bookings
      do not block a student or seat from rebooking.
    */

    active_student_id INT UNSIGNED
        GENERATED ALWAYS AS (
            CASE
                WHEN booking_status='ACTIVE'
                THEN student_id
                ELSE NULL
            END
        ) STORED,

    active_bus_trip_seat VARCHAR(100)
        GENERATED ALWAYS AS (
            CASE
                WHEN booking_status='ACTIVE'
                THEN CONCAT(
                    bus_id,
                    '-',
                    trip_type,
                    '-',
                    seat_number
                )
                ELSE NULL
            END
        ) STORED,

    active_otp_hash CHAR(64)
        GENERATED ALWAYS AS (
            CASE
                WHEN booking_status='ACTIVE'
                     AND otp_hash IS NOT NULL
                THEN otp_hash
                ELSE NULL
            END
        ) STORED,

    UNIQUE KEY uq_active_student(active_student_id),

    UNIQUE KEY uq_active_bus_trip_seat(
        active_bus_trip_seat
    ),

    UNIQUE KEY uq_active_otp(active_otp_hash),

    INDEX idx_booking_student(student_id),

    INDEX idx_booking_bus_trip(
        bus_id,
        trip_type,
        booking_status
    ),

    INDEX idx_booking_status(
        trip_status,
        booking_status
    ),

    CONSTRAINT fk_booking_student
        FOREIGN KEY(student_id)
        REFERENCES students(student_id),

    CONSTRAINT fk_booking_bus
        FOREIGN KEY(bus_id)
        REFERENCES buses(bus_id),

    CONSTRAINT fk_booking_area
        FOREIGN KEY(area_id)
        REFERENCES areas(area_id),

    CONSTRAINT chk_booking_seat
        CHECK(seat_number BETWEEN 1 AND 40)
) ENGINE=InnoDB;


/* =========================================================
   9. BUS REQUESTS
   Student requests for an additional bus/area.
   Admin can approve or reject and send a message.
   ========================================================= */

CREATE TABLE bus_requests (

    request_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    student_id INT UNSIGNED NOT NULL,

    area_id INT UNSIGNED NOT NULL,

    trip_type ENUM('MORNING','RETURN') NOT NULL,

    request_note VARCHAR(500),

    request_status ENUM(
        'PENDING',
        'APPROVED',
        'REJECTED'
    ) NOT NULL DEFAULT 'PENDING',

    admin_message VARCHAR(1000) NULL,

    reviewed_by_admin_id INT UNSIGNED NULL,

    reviewed_at DATETIME NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_request_status(
        request_status,
        created_at
    ),

    INDEX idx_request_student(
        student_id,
        request_status
    ),

    CONSTRAINT fk_request_student
        FOREIGN KEY(student_id)
        REFERENCES students(student_id),

    CONSTRAINT fk_request_area
        FOREIGN KEY(area_id)
        REFERENCES areas(area_id),

    CONSTRAINT fk_request_admin
        FOREIGN KEY(reviewed_by_admin_id)
        REFERENCES admins(admin_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;


/* =========================================================
   10. NOTIFICATION LOGS
   Tracks Telegram notification attempts.
   ========================================================= */

CREATE TABLE notification_logs (

    notification_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    booking_id BIGINT UNSIGNED NULL,

    student_id INT UNSIGNED NULL,

    notification_type ENUM(
        'BOARDED',
        'DROPPED',
        'BOOKING',
        'REQUEST_APPROVED',
        'REQUEST_REJECTED'
    ) NOT NULL,

    channel ENUM(
        'TELEGRAM',
        'WHATSAPP',
        'OTHER'
    ) NOT NULL DEFAULT 'TELEGRAM',

    recipient VARCHAR(100) NOT NULL,

    message TEXT NOT NULL,

    delivery_status ENUM(
        'PENDING',
        'SENT',
        'FAILED'
    ) NOT NULL DEFAULT 'PENDING',

    external_message_id VARCHAR(255) NULL,

    error_message TEXT NULL,

    sent_at DATETIME NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_notification_booking(booking_id),

    INDEX idx_notification_status(delivery_status),

    CONSTRAINT fk_notification_booking
        FOREIGN KEY(booking_id)
        REFERENCES bookings(booking_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_notification_student
        FOREIGN KEY(student_id)
        REFERENCES students(student_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;


/* =========================================================
   11. SAMPLE AREAS
   ========================================================= */

INSERT INTO areas(area_name) VALUES

('Tambaram'),
('Velachery'),
('Guindy'),
('Avadi'),
('Porur'),
('Anna Nagar'),
('T Nagar'),
('Adyar'),
('Chromepet'),
('Pallavaram'),
('Sholinganallur'),
('Perungudi'),
('Medavakkam'),
('Poonamallee'),
('Ambattur'),
('Mogappair'),
('Vadapalani'),
('Koyambedu'),
('Thiruvottiyur'),
('Guduvanchery');


/* =========================================================
   12. SAMPLE BUSES - 50
   ========================================================= */

INSERT INTO buses(
    bus_number,
    bus_name,
    registration_number,
    capacity
)

WITH RECURSIVE seq AS(
    SELECT 1 AS n

    UNION ALL

    SELECT n+1
    FROM seq
    WHERE n<50
)

SELECT
    CONCAT('BM',LPAD(n,3,'0')),
    CONCAT('Bus ',LPAD(n,3,'0')),
    CONCAT('TN01BM',LPAD(n,4,'0')),
    40

FROM seq;


/* =========================================================
   13. SAMPLE DRIVERS - 50
   ========================================================= */

INSERT INTO drivers(
    driver_code,
    full_name,
    email,
    password_hash,
    gender,
    bus_id,
    trip_type
)

WITH RECURSIVE seq AS(
    SELECT 1 AS n

    UNION ALL

    SELECT n+1
    FROM seq
    WHERE n<50
)

SELECT
    CONCAT('BM',LPAD(n,3,'0')),

    CONCAT('Driver ',LPAD(n,3,'0')),

    CONCAT(
        'driver',
        LPAD(n,3,'0'),
        '@busmate.demo'
    ),

    SHA2('123@RC',256),

    CASE
        WHEN MOD(n,2)=0 THEN 'FEMALE'
        ELSE 'MALE'
    END,

    CASE
        WHEN n<=40 THEN n
        ELSE NULL
    END,

    CASE
        WHEN MOD(n,2)=0 THEN 'RETURN'
        ELSE 'MORNING'
    END

FROM seq;


/* =========================================================
   14. SAMPLE STUDENTS - 500
   ========================================================= */

INSERT INTO students(
    full_name,
    roll_number,
    email,
    password_hash,
    gender
)

WITH RECURSIVE seq AS(
    SELECT 1 AS n

    UNION ALL

    SELECT n+1
    FROM seq
    WHERE n<500
)

SELECT

    CONCAT('Student ',LPAD(n,3,'0')),

    CONCAT('REC',LPAD(n,4,'0')),

    CONCAT(
        'student',
        LPAD(n,3,'0'),
        '@rajalakshmi.edu.in'
    ),

    SHA2('123@RC',256),

    CASE
        WHEN MOD(n,2)=0 THEN 'FEMALE'
        ELSE 'MALE'
    END

FROM seq;


/* =========================================================
   15. SAMPLE ADMIN
   ========================================================= */

INSERT INTO admins(
    full_name,
    email,
    password_hash
)

VALUES(
    'BusMate Administrator',
    'hariini2526@gmail.com',
    '!SET_SECURE_HASH_USING_JAVA_BEFORE_LOGIN!'
);


/* =========================================================
   16. MORNING AND RETURN CONFIGURATION
   ========================================================= */

INSERT INTO trip_configurations(
    trip_type,
    bus_id,
    driver_id,
    start_area_id,
    destination_area_id,
    start_time,
    description
)

VALUES

(
    'MORNING',
    1,
    1,
    1,
    20,
    '06:00:00',
    'College morning trip'
),

(
    'RETURN',
    2,
    2,
    20,
    1,
    '16:30:00',
    'College return trip'
);


/* =========================================================
   17. SAMPLE BUS ROUTES
   First 40 buses have routes.
   Last 10 buses are unallocated.
   ========================================================= */



INSERT INTO bus_routes (
    bus_id,
    area_id,
    trip_type,
    pickup_time,
    route_order
)
SELECT
    b.bus_id,
    a.area_id,
    t.trip_type,

    CASE
        WHEN t.trip_type = 'MORNING'
        THEN ADDTIME(
            '06:00:00',
            SEC_TO_TIME((a.area_id - 1) * 900)
        )
        ELSE ADDTIME(
            '16:30:00',
            SEC_TO_TIME((a.area_id - 1) * 900)
        )
    END,

    a.area_id

FROM buses b
CROSS JOIN areas a
CROSS JOIN (
    SELECT 'MORNING' AS trip_type
    UNION ALL
    SELECT 'RETURN' AS trip_type
) t

WHERE b.bus_id <= 40
AND MOD(
    CAST(a.area_id AS SIGNED) - 1
    - MOD(CAST(b.bus_id AS SIGNED) - 1, 20),
    20
) IN (0, 1);










/* =========================================================
   18. SAMPLE BOOKINGS
   First 10 buses: 40 bookings each.
   Next 20 buses: 20 bookings each.
   Next 10 buses: 10 bookings each.
   Last 10 buses: no bookings.

   5 morning bookings remain pending.
   5 return bookings remain pending.
   Others are already verified.
   ========================================================= */




INSERT INTO bookings (
    student_id,
    bus_id,
    trip_type,
    seat_number,
    area_id,
    booking_status,
    trip_status,
    otp_hash,
    otp_expires_at,
    otp_verified_at
)
SELECT
    student_id,
    bus_id,
    trip_type,
    seat_number,
    area_id,
    'ACTIVE',

    CASE
        WHEN MOD(booking_sequence, 50) = 0
        THEN 'BOOKED'
        WHEN trip_type = 'MORNING'
        THEN 'BOARDED'
        ELSE 'DROPPED'
    END,

    CASE
        WHEN MOD(booking_sequence, 50) = 0
        THEN SHA2(
            CASE
                WHEN trip_type = 'MORNING'
                THEN LPAD(1000 + MOD(booking_sequence, 1000), 4, '0')
                ELSE LPAD(5000 + MOD(booking_sequence, 1000), 4, '0')
            END,
            256
        )
        ELSE NULL
    END,

    CASE
        WHEN MOD(booking_sequence, 50) = 0
        THEN DATE_ADD(NOW(), INTERVAL 1 DAY)
        ELSE NULL
    END,

    CASE
        WHEN MOD(booking_sequence, 50) = 0
        THEN NULL
        ELSE NOW()
    END

FROM (
    SELECT
        b.bus_id,
        s.seat_number,

        ROW_NUMBER() OVER (
            ORDER BY b.bus_id, s.seat_number
        ) AS student_id,

        MOD(b.bus_id - 1, 20) + 1 AS area_id,

        CASE
            WHEN MOD(b.bus_id, 2) = 0
            THEN 'RETURN'
            ELSE 'MORNING'
        END AS trip_type,

        (b.bus_id - 1) * 40 + s.seat_number
            AS booking_sequence

    FROM buses b

    CROSS JOIN (
        SELECT 1 AS seat_number UNION ALL SELECT 2
        UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6
        UNION ALL SELECT 7 UNION ALL SELECT 8
        UNION ALL SELECT 9 UNION ALL SELECT 10
        UNION ALL SELECT 11 UNION ALL SELECT 12
        UNION ALL SELECT 13 UNION ALL SELECT 14
        UNION ALL SELECT 15 UNION ALL SELECT 16
        UNION ALL SELECT 17 UNION ALL SELECT 18
        UNION ALL SELECT 19 UNION ALL SELECT 20
    ) s

    WHERE
        (b.bus_id BETWEEN 1 AND 10)
        OR (b.bus_id BETWEEN 11 AND 30)
        OR (b.bus_id BETWEEN 31 AND 40)

) sample_bookings

WHERE student_id <= 500;



SELECT
    bus_id,
    COUNT(*) AS booked_seats
FROM bookings
WHERE booking_status = 'ACTIVE'
GROUP BY bus_id
ORDER BY bus_id;




/* =========================================================
   19. DEMO OTP TEST RECORDS

   Pending records use known OTP values:
   Morning: 1001, 1002, 1003, 1004, 1005
   Return:  5001, 5002, 5003, 5004, 5005

   OTP is stored as SHA-256 hash.
   Java must compare SHA2(entered OTP,256).
   ========================================================= */


/* =========================================================
   20. SAMPLE BUS REQUESTS
   ========================================================= */

INSERT INTO bus_requests(
    student_id,
    area_id,
    trip_type,
    request_note,
    request_status,
    admin_message,
    reviewed_by_admin_id,
    reviewed_at
)

VALUES

(
    450,19,'MORNING',
    'Requesting a morning bus for this area.',
    'PENDING',NULL,NULL,NULL
),

(
    451,20,'RETURN',
    'Need a return trip bus.',
    'PENDING',NULL,NULL,NULL
),

(
    452,18,'MORNING',
    'Students from this area need transport.',
    'APPROVED',
    'Your request has been approved. The administration will arrange the bus.',
    1,NOW()
),

(
    453,17,'RETURN',
    'Requesting evening transportation.',
    'REJECTED',
    'Your request has been reviewed. No additional bus is currently available.',
    1,NOW()
);


/* =========================================================
   21. VERIFICATION COUNTS
   ========================================================= */

SELECT 'Students' AS item,COUNT(*) AS total FROM students

UNION ALL

SELECT 'Drivers',COUNT(*) FROM drivers

UNION ALL

SELECT 'Buses',COUNT(*) FROM buses

UNION ALL

SELECT 'Areas',COUNT(*) FROM areas

UNION ALL

SELECT 'Routes',COUNT(*) FROM bus_routes

UNION ALL

SELECT 'Bookings',COUNT(*) FROM bookings

UNION ALL

SELECT 'Admins',COUNT(*) FROM admins

UNION ALL

SELECT 'Bus Requests',COUNT(*) FROM bus_requests;


/* =========================================================
   22. BUS OCCUPANCY
   ========================================================= */

SELECT

    b.bus_number,

    b.capacity,

    COUNT(
        CASE
            WHEN bk.booking_status='ACTIVE'
            THEN 1
        END
    ) AS booked_seats,

    b.capacity-COUNT(
        CASE
            WHEN bk.booking_status='ACTIVE'
            THEN 1
        END
    ) AS available_seats

FROM buses b

LEFT JOIN bookings bk
    ON b.bus_id=bk.bus_id

GROUP BY
    b.bus_id,
    b.bus_number,
    b.capacity

ORDER BY b.bus_id;


/* =========================================================
   23. TRIP VERIFICATION SUMMARY
   ========================================================= */

SELECT

    trip_type,

    COUNT(*) AS total_bookings,

    SUM(trip_status='BOARDED') AS boarded,

    SUM(trip_status='DROPPED') AS dropped,

    SUM(trip_status='BOOKED') AS pending_verification

FROM bookings

WHERE booking_status='ACTIVE'

GROUP BY trip_type;


/* =========================================================
   24. STUDENT REQUEST SUMMARY
   ========================================================= */

SELECT

    request_status,

    COUNT(*) AS total_requests

FROM bus_requests

GROUP BY request_status;


/* =========================================================
   END OF BUSMATE V2 DATABASE
   ========================================================= */
   
   
   
   
   
   
   -- Run this once in MySQL Workbench after selecting busmate_v2.
-- Adds a temporary plain OTP field so the active student dashboard can display it.
-- OTP is cleared immediately after driver verification or cancellation.
ALTER TABLE bookings
    MODIFY booking_status ENUM('ACTIVE','CANCELLED','COMPLETED') NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN otp_code CHAR(4) NULL AFTER otp_hash;

-- Existing active bookings cannot have their original OTP recovered because
-- only the SHA-256 hash was stored. They must be cancelled/rebooked after migration.
UPDATE bookings
SET booking_status='COMPLETED'
WHERE booking_status='ACTIVE'
  AND trip_status IN ('BOARDED','DROPPED');
  
  
  
  
  
  
  
  
  USE busmate_v2;

SELECT
    bk.booking_id,
    s.full_name,
    s.email,
    b.bus_number,
    bk.trip_type,
    bk.seat_number,
    bk.trip_status,
    bk.otp_code
FROM bookings bk
JOIN students s ON s.student_id = bk.student_id
JOIN buses b ON b.bus_id = bk.bus_id
WHERE bk.booking_status = 'ACTIVE'
  AND bk.trip_status = 'BOOKED'
ORDER BY bk.booking_id;

UPDATE bookings
SET
    otp_hash = SHA2('1234', 256),
    otp_code = '1234',
    otp_expires_at = DATE_ADD(NOW(), INTERVAL 1 DAY),
    booking_status = 'ACTIVE',
    trip_status = 'BOOKED'
WHERE booking_id = 641;