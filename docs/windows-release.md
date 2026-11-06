# SmartHire for Windows

Install the [Java 21 JDK](https://adoptium.net/temurin/releases/?version=21) and add its `bin` directory to `PATH`. Open a new PowerShell window and run `java -version` to check it. Extract the entire ZIP into a folder you can write to. The ZIP includes the app, Java libraries, and demo files.

## Connect to Aiven

The cloud Windows ZIP contains `SmartHire-Cloud.cmd` and the Aiven configuration example. The local ZIP contains `SmartHire.cmd`.

If you already run SmartHire from source with Aiven, copy `aiven.properties` and `aiven-truststore.p12` from the checkout's `.local` folder to the ZIP's `.local` folder and go to step 4.

1. Open your MySQL service in the Aiven Console. Copy the host, port, database name, username, and password from the connection details. Download the [project CA certificate](https://aiven.io/docs/platform/concepts/tls-ssl-certificates).
2. From PowerShell in the extracted folder, create the private configuration file from the example. Save the downloaded certificate as `.local/aiven-ca.pem`.

   ```powershell
   New-Item -ItemType Directory -Force .local
   if (!(Test-Path .local/aiven.properties)) {
       Copy-Item aiven.properties.example .local/aiven.properties
   }
   keytool -importcert -alias aiven-project-ca -file .local/aiven-ca.pem -keystore .local/aiven-truststore.p12 -storetype PKCS12 -storepass changeit
   ```

   When `keytool` prompts you to trust the certificate, check that it matches the CA downloaded from Aiven and enter `yes`.
3. Edit `.local/aiven.properties`. Fill in `jdbcUrl` as `jdbc:mysql://HOST:PORT/DATABASE` and put the username and password on their own lines. Keep this file private.
4. Check the connection, then open SmartHire. For an existing installation, carry over the private `.local/aiven-documents` folder to retain access to originals.

   ```powershell
   .\SmartHire-Cloud.cmd --check-database
   .\SmartHire-Cloud.cmd
   ```

When setting up a fresh Aiven database or upgrading an older SmartHire schema, close SmartHire clients and back up the database and document folder first. Run `.\SmartHire-Cloud.cmd --upgrade-database` once with a database account that can create and alter the schema. Use the updated launcher for subsequent sign-ins. The connection check verifies TLS connectivity and regular startup checks the schema version.

SmartHire checks the server certificate and hostname. Uploaded CVs and circulars stay in `.local/aiven-documents` on this computer. A second computer can connect to the same database and needs access to the same original document files to open them.

Use your cloud SmartHire sign-in. To seed a fresh Aiven database, create `.local/aiven-demo-accounts.properties` with separate `adminPassword` and `demoPassword` values, each at least 16 characters. Run `.\SmartHire-Cloud.cmd --seed-demo`. The admin uses `adminPassword`; fictional students and companies use `demoPassword`.

## Use local MySQL

Install [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/8.4.html). Set an administrator password, configure local TCP port `3306`, and start the MySQL service. Run the SQL below in the MySQL client or [MySQL Workbench](https://dev.mysql.com/downloads/workbench/).

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

Open PowerShell in the extracted folder and run these commands.

```powershell
.\SmartHire.cmd --seed-demo
.\SmartHire.cmd
```

On a fresh database, the seed creates eight fictional accounts, eight postings and the shared skills and categories. Run the seed once to populate a demo database. It preserves existing data on subsequent runs.

Sign in as `student@smarthire.example`, `company@smarthire.example`, or `admin@smarthire.example` with the local demo password `SmartHireDemo!21`. Open `demo data/Demo accounts.md` for the full list and use the PDFs beside it to try CV review and circular upload. A student confirms a CV under **My CV**, then an admin approves that version under **CV review** before the student can apply to an eligible posting.

If the sign-in screen reports a database error, check that the local MySQL service is running on port `3306` and that `.local/application.properties` matches the MySQL account. Close an existing SmartHire window before launching a second copy from the same folder.
