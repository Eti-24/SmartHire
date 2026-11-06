# SmartHire

SmartHire is a Java Swing app for student recruitment. Students upload a CV, compare and save jobs, and track their applications and interviews. Companies post jobs, review applications, and record interview feedback and hiring decisions. Admins approve companies, jobs, and CV versions.

Open a match to see which qualifications raised the score and which mandatory requirements are still missing. When a student applies, SmartHire saves the CV version, contact details, and match result with the application.

## Run the Windows release with Aiven

For the published ZIP, follow the instructions on the [release page](https://github.com/Eti-24/SmartHire/releases). To build and run the cloud ZIP from this checkout, use these steps.

1. Install the [Java 21 JDK](https://adoptium.net/temurin/releases/?version=21), load `scripts/dev-env.ps1`, and run `.\scripts\package-release.ps1 -Cloud`. Extract the resulting ZIP from `target`.
2. Follow [Connect to Aiven](docs/windows-release.md#connect-to-aiven) to add your connection settings and CA certificate. The ZIP includes the same instructions.
3. Open PowerShell in the extracted folder. Check the connection, then launch the app.

```powershell
.\SmartHire-Cloud.cmd --check-database
.\SmartHire-Cloud.cmd
```

For a fresh or older Aiven schema, follow the database upgrade steps in the setup guide before launching. Use the separate local Windows ZIP with [local MySQL](docs/windows-release.md#use-local-mysql).

## Try the demo

The seed creates eight fictional accounts, eight job postings, and shared categories and skills on a fresh database. The local demo password is `SmartHireDemo!21`.

| Role | Sign-in email |
| --- | --- |
| Student | `student@smarthire.example` |
| Company | `company@smarthire.example` |
| Admin | `admin@smarthire.example` |

To try an application, sign in as a student and upload a [sample CV](demo%20data/Demo%20accounts.md) under **My CV**. Confirm the qualifications, switch to the admin account, and approve that version under **CV review**. Sign back in as the student and apply to a job you're eligible for.

The [demo walkthrough](docs/demo-guide.md) gives an example job and the scores to expect from each CV. Use the [sample job circular](output/pdf/demo-job-circular.pdf) if you want to try an upload as a company. You can run the seed again without clearing the records already in MySQL.

## Set up the project locally

On Windows x64, install [Git](https://git-scm.com/downloads) and [PowerShell 7](https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-windows). MySQL also needs the [Microsoft Visual C++ x64 Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist). Open PowerShell 7 and run these commands.

```powershell
git clone https://github.com/Eti-24/SmartHire.git
Set-Location SmartHire
.\scripts\setup-local.ps1 -SeedDemo
.\scripts\run.ps1
```

The setup script prints each step. It installs portable Java 21, Maven, and MySQL under `.tools`, configures and starts MySQL on port `3306`, and builds the app. `-SeedDemo` loads the sample accounts and jobs. You can rerun setup with the same database. After a restart, run `.\scripts\start-mysql.ps1` before opening the app. If you already have MySQL running on port `3306`, use the Workbench steps below.

## Set up local MySQL in Workbench

Install [MySQL Workbench](https://dev.mysql.com/downloads/workbench/). If you already ran `setup-local.ps1`, start the project’s MySQL with `.\scripts\start-mysql.ps1` and skip to step 4. The setup script created the database and application account. Use the generated password in `.local/database.json` for the Workbench connection.

1. **Start MySQL Server.** Install [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/8.4.html). In MySQL Configurator, enable TCP/IP on port `3306`, set a root password, and configure MySQL as a Windows service. Check that the service is running in Windows **Services**. Workbench connects to the running server.

2. **Connect as an administrator.** On the Workbench home screen, click **+** next to **MySQL Connections**. Enter `SmartHire setup` as the connection name, choose **Standard (TCP/IP)**, set **Hostname** to `127.0.0.1`, **Port** to `3306`, and **Username** to `root`. Leave **Default Schema** blank. Click **Test Connection**, enter the MySQL root password, then click **OK** and open the connection. [Workbench connection tutorial](https://dev.mysql.com/doc/workbench/en/wb-getting-started-tutorial-create-connection.html)

3. **Create the database and app user.** Paste the SQL below into the query tab. Replace `YOUR_LOCAL_PASSWORD` with a new password for the `smarthire` account. Use a password without quotes, backslashes, or whitespace so it works in both examples. Click the lightning bolt to run the script. If this exact user already exists, omit `CREATE USER` and use its existing password in the next steps.

   ```sql
   CREATE DATABASE IF NOT EXISTS smarthire CHARACTER SET utf8mb4;
   CREATE USER 'smarthire'@'127.0.0.1' IDENTIFIED BY 'YOUR_LOCAL_PASSWORD';
   GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
       ON smarthire.* TO 'smarthire'@'127.0.0.1';
   ```

4. **Test the app account.** Add a second Workbench connection called `SmartHire local`. Use the same hostname and port, with **Username** `smarthire` and **Default Schema** `smarthire`. Click **Test Connection** and enter the app user’s password. Open the connection and run `SELECT DATABASE(), CURRENT_USER();`. The selected database should be `smarthire`.

5. **Give SmartHire the connection settings.** In the extracted local ZIP folder or the source checkout, create `.local/application.properties`. Save it as a plain-text `.properties` file with the same password you used in step 4. If the setup script already created the file in your checkout, use that file as is. Keep it private.

   ```properties
   user=smarthire
   password=YOUR_LOCAL_PASSWORD
   ```

6. **Start SmartHire.** If you used `setup-local.ps1`, launch from the checkout with `.\scripts\run.ps1`. If you downloaded the [local Windows ZIP](https://github.com/Eti-24/SmartHire/releases/latest), install [Java 21](https://adoptium.net/temurin/releases/?version=21) and run these commands from the extracted folder. The seed command is optional.

   ```powershell
   .\SmartHire.cmd --check-database
   .\SmartHire.cmd --seed-demo
   .\SmartHire.cmd
   ```

   If you’re building from source with your own MySQL installation, put Java 21 and [Maven](https://maven.apache.org/install.html) on `PATH`. Run these commands from the checkout. Omit the `--seed-demo` command if you want to start with an empty database.

   ```powershell
   mvn package dependency:copy-dependencies '-DincludeScope=runtime'
   java -cp 'target/classes;target/dependency/*' smarthire.Main --seed-demo
   java -cp 'target/classes;target/dependency/*' smarthire.Main
   ```

7. **Check the tables.** In Workbench, refresh **Schemas**, then expand **smarthire > Tables**. SmartHire creates its tables on first launch or when you run the seed. If Workbench cannot connect, check that MySQL is listening on `127.0.0.1:3306`. If Workbench connects and SmartHire reports an error, check `.local/application.properties` in the folder you launched from.

Maven downloads dependencies on the first build. With Java and Maven on `PATH`, run `pwsh -File scripts/package-release.ps1` to build the local Windows ZIP in `target`.

## Tests and project guides

- `mvn test` runs unit tests. Create a separate `smarthire_test` database with the same grants before running `mvn '-Dsmarthire.integration=true' test`. The app and tests read the local database credentials from `.local/application.properties`.
- The [reproduction guide](docs/reproduce-demo.md) covers the project-local tools, optional Aiven profile, database smoke test, and backups.
- The [database diagram](docs/database/er-diagram.html) shows the MySQL schema. [Implementation status](docs/implementation-status.md) records test results and manual checks.
