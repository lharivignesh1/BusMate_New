# 🚌 BusMate – Smart College Bus Booking System

<p align="center">
  <strong>Smart Travel. Easy Booking. Better Campus Connectivity.</strong>
</p>

<p align="center">
  A Java-based college bus management and seat booking platform designed to simplify daily student transportation.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?style=for-the-badge&logo=openjdk" />
  <img src="https://img.shields.io/badge/MySQL-Database-blue?style=for-the-badge&logo=mysql" />
  <img src="https://img.shields.io/badge/Maven-Build-red?style=for-the-badge&logo=apachemaven" />
  <img src="https://img.shields.io/badge/HTML-CSS-JavaScript-yellow?style=for-the-badge&logo=html5" />
</p>

---

## 🚀 About the Project

**BusMate** is a Smart College Bus Booking System that digitizes the traditional college transportation process.

It provides a centralized platform where students can select their bus routes, view available seats, and book their preferred seats. Drivers can manage passenger information, while administrators can manage buses, routes, students, and drivers.

The system supports separate **Morning and Return trips**, making daily transportation management more organized and efficient.

## ✨ Key Features

### 👨‍🎓 Student Portal
- Student login and authentication.
- Morning and Return trip selection.
- View available buses and routes.
- Select pickup areas.
- Interactive seat selection interface.
- Book available seats.
- OTP-based booking verification.
- View booking details and bus information.
- Cancel or manage bookings according to system rules.
- Submit bus-related requests.

### 🚌 Driver Portal
- Driver login and authentication.
- View assigned bus information.
- Morning and Return trip management.
- View passenger and booking details.
- Monitor seat occupancy.
- Clear booking data for the assigned bus and trip.
- Manage passenger information.

### 🛡️ Admin Dashboard
- Secure administrator login.
- Manage student records.
- Manage driver records.
- Add, update, and delete buses.
- Manage routes and pickup areas.
- Configure Morning and Return trip details.
- Manage bus and transportation information.
- View and manage system records.

## 🛠️ Technology Stack

| Category | Technologies |
|---|---|
| Frontend | HTML, CSS, JavaScript |
| Backend | Java |
| Database | MySQL |
| Database Connectivity | JDBC |
| Build Tool | Apache Maven |
| Development Environment | Visual Studio Code |
| Version Control | Git & GitHub |

## 🏗️ Project Architecture

```text
                 BUSMATE
                    |
          ---------------------
          |                   |
      FRONTEND             BACKEND
          |                   |
     HTML / CSS          Java Server
     JavaScript               |
          |                  JDBC
          |                   |
          ---------------------
                    |
                  MySQL
                 Database
```

## 📂 Project Structure

```text
BusMate/
│
├── backend/
│   ├── src/
│   │   └── main/
│   │       └── java/
│   │           └── busmate/
│   │               └── BusMateServer.java
│   │
│   ├── pom.xml
│   └── target/
│
├── images/
│
├── .github/
│
├── home.html
├── login.html
├── student.html
├── driver.html
├── admin.html
├── about.html
│
├── busmate_v2.sql
├── Dockerfile
├── .gitignore
└── README.md
```

## ⚙️ Installation & Setup

Follow these steps to run BusMate locally.

### 1. Clone the Repository

```bash
git clone https://github.com/lharivignesh1/BusMate_New.git
```

### 2. Navigate to the Project

```bash
cd BusMate_New
```

### 3. Configure MySQL Database

1. Install and start MySQL Server.
2. Open MySQL Workbench or MySQL Command Line.
3. Create the database:

```sql
CREATE DATABASE busmate_v2;
```

4. Import the provided `busmate_v2.sql` file into the database.

### 4. Configure Database Credentials

Set your database password as an environment variable.

**Windows CMD:**

```cmd
set BUSMATE_DB_PASSWORD=your_mysql_password
set BUSMATE_ADMIN_PASSWORD=your_admin_password
```

Replace the example values with your actual credentials.

Configure the database username and connection details in the backend if required.

### 5. Run the Backend

Navigate to the project root and execute:

```bash
mvn -f backend/pom.xml clean compile exec:java
```

The backend server starts on:

```text
http://localhost:8080
```

### 6. Open the Frontend

Open `home.html` in your browser or use the VS Code Live Server extension if your frontend setup requires it.

Make sure the backend is running before using features that require database connectivity.

## 🔄 How BusMate Works

```text
        STUDENT LOGIN
              |
       SELECT TRIP TYPE
              |
       MORNING / RETURN
              |
       SELECT BUS & ROUTE
              |
       CHOOSE PICKUP AREA
              |
        SELECT SEAT
              |
       OTP VERIFICATION
              |
       BOOKING CONFIRMED
              |
       VIEW BOOKING DETAILS
```

Drivers manage their assigned trips and passenger information, while administrators control the overall transportation system.

## 🔐 Security & Data Management

- Role-based access for students, drivers, and administrators.
- Database-backed authentication.
- OTP-based booking verification.
- MySQL database integration.
- Environment variables for sensitive credentials.
- Database constraints to maintain booking consistency.

## 🎯 Project Objectives

- Digitize college transportation management.
- Simplify student bus seat reservations.
- Reduce manual booking and seat allocation.
- Improve communication between students and drivers.
- Provide centralized transportation administration.
- Maintain organized booking records.

## 🔮 Future Enhancements

- Live GPS-based bus tracking.
- Real-time bus arrival notifications.
- Mobile application integration.
- Online payment integration.
- Advanced transportation analytics.
- AI-powered route optimization.

## 👨‍💻 Developer

**Hari Vignesh L.**

Artificial Intelligence and Data Science  
Rajalakshmi Engineering College, Chennai.

<p>
  <a href="https://github.com/lharivignesh1">
    <img src="https://img.shields.io/badge/GitHub-lharivignesh1-black?style=for-the-badge&logo=github" />
  </a>
  <a href="https://www.linkedin.com/in/lharivignesh1">
    <img src="https://img.shields.io/badge/LinkedIn-Connect-blue?style=for-the-badge&logo=linkedin" />
  </a>
</p>

---

<p align="center">
  <strong>🚌 BusMate – Making College Transportation Smarter!</strong>
  <br>
  <i>Designed to make every campus journey easier.</i>
</p>
