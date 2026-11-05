# Quizzy – Online Examination System

Quizzy is a web-based online examination platform built with Java and Spring Boot. It provides separate experiences for students and administrators, with examination management, timed tests, result processing, and student performance tracking.

## Features

### Student

- Student registration, login, and logout.
- Personalized student dashboard.
- Browse and attend available examinations.
- Timed examinations with automatic submission.
- View examination history.
- View results after administrator publication.
- Submit examination feedback.
- Manage notification preferences.
- Access student support features.

### Administrator

- Secure administrator login.
- Manage examination questions.
- Create and manage scheduled examinations.
- Monitor examination attempts.
- Review student examination results.
- Publish results to students.
- View analytics and student performance.
- Manage announcements and feedback.

## Technology Stack

- **Language:** Java 21
- **Backend:** Spring Boot 4.2.0-SNAPSHOT
- **Frontend:** HTML, CSS, JavaScript, Thymeleaf
- **Database:** H2 Database
- **Persistence:** Spring Data JPA, Hibernate
- **Security:** Spring Security and password hashing
- **Build Tool:** Maven
- **Testing:** JUnit and Spring Boot Test

## Prerequisites

Install the following:

1. Java Development Kit (JDK) 21
2. Git
3. Maven Wrapper (included in this project)

Verify Java:

```powershell
java -version
```

## Installation and Setup

Clone the repository:

```powershell
git clone YOUR_GITHUB_REPOSITORY_URL
```

Move into the project folder:

```powershell
cd online.exam
```

Build the project:

```powershell
.\mvnw.cmd -DskipTests compile
```

Run the application:

```powershell
.\mvnw.cmd spring-boot:run
```

Open the application:

```text
http://localhost:8081
```

## Running Tests

Run the automated tests:

```powershell
.\mvnw.cmd test
```

Tests should use the test profile and an isolated in-memory database rather than the local production database.

## Database and Configuration

Quizzy uses a file-backed H2 database.

Default local database location:

```text
./data/quizzy
```

The database file is:

```text
data/quizzy.mv.db
```

Important:

- Never upload the database file to GitHub.
- Never commit passwords, email credentials, or private configuration.
- Configure sensitive values using environment variables or a private local configuration file.
- Back up the database before making schema or data changes.
- Do not use production data for destructive testing.

## Project Structure

```text
online.exam/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/online/exam/
│   │   │       ├── controller classes
│   │   │       ├── entity/
│   │   │       ├── repository/
│   │   │       └── service/
│   │   └── resources/
│   │       ├── static/
│   │       │   ├── css/
│   │       │   └── js/
│   │       └── templates/
│   └── test/
├── scripts/
├── .gitignore
├── mvnw
├── mvnw.cmd
└── pom.xml
```

## Project Status

Quizzy is under active development. Features and documentation may evolve as the project improves.

## License

No open-source license has been selected yet. All rights are reserved unless a license is added to this repository.
