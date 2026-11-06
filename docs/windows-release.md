# SmartHire for Windows

## Install the prerequisites

1. Install [Java 21](https://adoptium.net/temurin/releases/?version=21) for Windows and add its `bin` directory to `PATH`. Open a new terminal and confirm `java -version` reports Java 21.
2. Install [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/8.4.html). Configure it to listen locally on TCP port `3306`, set an administrator password during installation, and start the MySQL service. You can run the SQL below in the MySQL client or [MySQL Workbench](https://dev.mysql.com/downloads/workbench/).
3. Extract the entire SmartHire ZIP into a folder you can write to. The ZIP includes the app, Java libraries, and fictional demo files. Maven is only needed when building from source.

## Configure MySQL

Sign in to the local MySQL server as an administrator. Create the database and a dedicated account. Replace `YOUR_LOCAL_PASSWORD` with a new password for that account. If the account already exists, use its existing password and omit `CREATE USER`.

```sql
CREATE DATABASE IF NOT EXISTS smarthire CHARACTER SET utf8mb4;
CREATE USER 'smarthire'@'127.0.0.1' IDENTIFIED BY 'YOUR_LOCAL_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON smarthire.* TO 'smarthire'@'127.0.0.1';
```

In the SmartHire folder, create a directory named `.local`. Save a plain-text file named `application.properties` there, using the same account and password. Use a password without backslashes or whitespace for the example below, and make sure the filename ends in `.properties`.

```properties
user=smarthire
password=YOUR_LOCAL_PASSWORD
```

SmartHire creates its tables on first startup. It stores original uploaded CVs and circulars in `.local/documents`. Keep `.local` private and back up the document folder together with the database.

## Seed the demo and launch

Open PowerShell in the extracted folder and run:

```powershell
.\SmartHire.cmd --seed-demo
.\SmartHire.cmd
```

On a fresh database, the seed creates eight fictional accounts, eight postings and the shared skills and categories. Run the seed once to populate a demo database. It preserves existing data on subsequent runs.

Sign in as `student@smarthire.example`, `company@smarthire.example`, or `admin@smarthire.example` with the local demo password `SmartHireDemo!21`. Open `demo data/Demo accounts.md` for the full list and use the PDFs beside it to try CV review and circular upload. A student confirms a CV under **My CV**, then an admin approves that version under **CV review** before the student can apply to an eligible posting.

If the sign-in screen reports a database error, check that the local MySQL service is running on port `3306` and that `.local/application.properties` matches the MySQL account. Close an existing SmartHire window before launching a second copy from the same folder.
