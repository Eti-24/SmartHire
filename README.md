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

The setup script prints each step. It installs portable Java 21, Maven, and MySQL under `.tools`, configures and starts MySQL on port `3306`, and builds the app. `-SeedDemo` loads the sample accounts and jobs. You can rerun setup with the same database. If another MySQL server already uses port `3306`, use the manual setup below.

After a restart, run `.\scripts\start-mysql.ps1` before opening the app. To use an existing Java, [Maven](https://maven.apache.org/install.html), and MySQL installation, follow [Configure MySQL](docs/windows-release.md#configure-mysql) in the checkout, then build and run from the project root.

```powershell
mvn package dependency:copy-dependencies '-DincludeScope=runtime'
java -cp 'target/classes;target/dependency/*' smarthire.Main --seed-demo
java -cp 'target/classes;target/dependency/*' smarthire.Main
```

Maven downloads dependencies on the first build. With Java and Maven on `PATH`, run `pwsh -File scripts/package-release.ps1` to build the local Windows ZIP in `target`.

## Tests and project guides

- `mvn test` runs unit tests. Create a separate `smarthire_test` database with the same grants before running `mvn '-Dsmarthire.integration=true' test`. The app and tests read the local database credentials from `.local/application.properties`.
- The [reproduction guide](docs/reproduce-demo.md) covers the project-local tools, optional Aiven profile, database smoke test, and backups.
- The [database diagram](docs/database/er-diagram.html) shows the MySQL schema. [Implementation status](docs/implementation-status.md) records test results and manual checks.
