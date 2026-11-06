# SmartHire

SmartHire is a Java Swing app for student recruitment. Students upload a CV, compare and save jobs, and track their applications and interviews. Companies post jobs, review applications, and record interview feedback and hiring decisions. Admins approve companies, jobs, and CV versions.

Open a match to see which qualifications raised the score and which mandatory requirements are still missing. When a student applies, SmartHire saves the CV version, contact details, and match result with the application.

## Run the Windows release

1. Install [Java 21](https://adoptium.net/temurin/releases/?version=21) and [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/8.4.html).
2. Download and extract the Windows ZIP from [GitHub Releases](https://github.com/Eti-24/SmartHire/releases).
3. Follow the [Windows setup guide](docs/windows-release.md) to create the local database and configure its application user. The ZIP includes a copy of the guide.
4. Open PowerShell in the extracted folder. Run the first command to load the sample accounts and jobs. The second opens SmartHire.

```powershell
.\SmartHire.cmd --seed-demo
.\SmartHire.cmd
```

The release includes the app's Java libraries. GitHub requires an account with access to this private repository to download it.

## Try the demo

The seed creates eight fictional accounts, eight job postings, and shared categories and skills on a fresh database. The local demo password is `SmartHireDemo!21`.

| Role | Sign-in email |
| --- | --- |
| Student | `student@smarthire.example` |
| Company | `company@smarthire.example` |
| Admin | `admin@smarthire.example` |

To try an application, sign in as a student and upload a [sample CV](demo%20data/Demo%20accounts.md) under **My CV**. Confirm the qualifications, switch to the admin account, and approve that version under **CV review**. Sign back in as the student and apply to a job you're eligible for.

The [demo walkthrough](docs/demo-guide.md) gives an example job and the scores to expect from each CV. Use the [sample job circular](output/pdf/demo-job-circular.pdf) if you want to try an upload as a company. You can run the seed again without clearing the records already in MySQL.

## Build and run from source

Install the same Java and MySQL prerequisites, plus [Apache Maven](https://maven.apache.org/install.html) and [Git](https://git-scm.com/downloads). Check `java -version` and `mvn -version` in a new PowerShell window. NetBeans and MySQL Workbench are optional.

```powershell
git clone https://github.com/Eti-24/SmartHire.git
Set-Location SmartHire
```

Complete [Configure MySQL](docs/windows-release.md#configure-mysql) in the checkout. Then build, seed, and run from the project root.

```powershell
mvn package dependency:copy-dependencies '-DincludeScope=runtime'
java -cp 'target/smarthire-0.1.0.jar;target/dependency/*' smarthire.Main --seed-demo
java -cp 'target/smarthire-0.1.0.jar;target/dependency/*' smarthire.Main
```

Maven downloads dependencies on the first build. To build the Windows ZIP, run `pwsh -File scripts/package-release.ps1` with Java and Maven on `PATH`. The ZIP is written to `target`.

## Tests and project guides

- `mvn test` runs unit tests. Create a separate `smarthire_test` database with the same grants before running `mvn '-Dsmarthire.integration=true' test`. The app and tests read the local database credentials from `.local/application.properties`.
- The [reproduction guide](docs/reproduce-demo.md) covers the project-local tools, optional Aiven profile, database smoke test, and backups.
- The [database diagram](docs/database/er-diagram.html) shows the MySQL schema. [Implementation status](docs/implementation-status.md) records test results and manual checks.
