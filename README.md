# SmartHire

SmartHire is a desktop recruitment app built with Java 21, Swing, and MySQL. Students upload CVs, see how their qualifications match a job, save postings, and track applications and interviews. Companies publish vacancies, review applicants, and record interview feedback and hiring decisions. Administrators review companies, postings, and CV versions.

A match score shows how each qualification contributes. Mandatory requirements determine eligibility. Each application keeps a snapshot of the submitted CV, contact details, and assessment.

## Run the Windows release

1. Install [Java 21](https://adoptium.net/temurin/releases/?version=21) and [MySQL Community Server 8.4 LTS](https://dev.mysql.com/downloads/mysql/8.4.html).
2. Download and extract the Windows ZIP from [GitHub Releases](https://github.com/Eti-24/SmartHire/releases).
3. Follow the [Windows setup guide](docs/windows-release.md) to create the local database and configure its application user. The ZIP includes a copy of the guide.
4. Open PowerShell in the extracted folder. Run the first command once to populate the fictional demo, then use the second command to open the app.

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

See the [demo accounts and sample CVs](demo%20data/Demo%20accounts.md), the [sample job circular](output/pdf/demo-job-circular.pdf), and the [guided walkthrough](docs/demo-guide.md). To try a submission, upload a sample under **My CV**, confirm the reviewed qualifications, approve that CV version from the admin's **CV review** screen, then apply as the student to an eligible posting. Repeating the seed preserves existing records.

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
