# SmartHire development environment

For clients using their existing NetBeans and an independent generated-password MySQL server, follow [Client local setup](client-local-setup.md). The root CMD entry points cover initial setup, explicit confirmed demo reset and starting MySQL before opening the installed IDE after reboot.

This guide records the project-local Windows development environment. For application setup, database configuration, and verification on another machine, see [Reproducing and verifying the demo](reproduce-demo.md).

## Installed tools

Tools use project-local portable installations under `E:\SmartHire\.tools`. They are not registered as system-wide applications. Workbench is an administrative extraction of Oracle's signed MSI, launched through the supplied script.

| Tool | Verified version | Location |
| --- | --- | --- |
| Eclipse Temurin JDK | 21.0.13+9 | `.tools\jdk-21.0.13+9` |
| Apache Maven | 3.10.0 | `.tools\apache-maven-3.10.0` |
| Apache NetBeans | 31 | `.tools\netbeans` |
| MySQL Community Server | 8.4.13 | `.tools\mysql-8.4.13-winx64` |
| MySQL Workbench | 26.10.0 | `.tools\workbench\MySQL\MySQL Workbench` |
| Git, already present | 2.55.0.windows.2 | `D:\Git` |

The existing Visual C++ x64 runtime was present; reinstalling it was unnecessary. Java selection is local to the developer shell and NetBeans configuration rather than a global PATH replacement.

## Start working

Run these commands in PowerShell 7 (`pwsh`) from `E:\SmartHire`:

```powershell
. .\scripts\dev-env.ps1
java -version
mvn -version
.\scripts\start-mysql.ps1
.\scripts\start-netbeans.ps1
```

Open Workbench when needed:

```powershell
.\scripts\start-workbench.ps1
```

In Workbench create a local TCP connection with host `127.0.0.1`, the port in `.local/database.json` (3307 for new setups), username `smarthire`, and schema `smarthire`. Obtain its generated password locally from `.local\database.json`; it is not included in this guide. Root administration credentials are separate in `.local\mysql-admin.ini`.

Stop the database when finished:

```powershell
.\scripts\stop-mysql.ps1
```

MySQL is an on-demand process rather than an automatically starting Windows service. It was left running after setup. Restart it with the script after reboot. The IDE and Workbench were not left open. On the 8 GB laptop, open Workbench only when needed alongside the IDE.

The application source and root Maven build are now implemented. Use README.md and docs/reproduce-demo.md for current launch/verification instructions. The earlier setup smoke program under `.tools\dependency-check` remains a tooling check rather than the application.

## E-drive storage

- Maven repository: `.tools\maven-repository`, selected by `dev-env.ps1`.
- Maven user configuration: `.tools\maven-user`.
- NetBeans user settings/cache: `.tools\netbeans-user` and `.tools\netbeans-cache`, also set in the installed IDE configuration.
- Workbench user settings: `.tools\workbench-user`; bundled MySQL Shell configuration: `.tools\workbench-shell-user`, selected by the launch script.
- Database configuration, credentials, logs, and data: `.local`; server data is in `.local\mysql-data`.
- Verified original download archives: `.tools\downloads`; temporary transfer chunks were removed after checksum verification.

Windows can still use its normal temporary directories and credential services on C:. The installed tools, main caches, and database data are on E:.

`.tools`, `.local`, build output, and logs are excluded through `.gitignore`. Treat `.local` as private. When moving this setup, preserve both the database and managed application files; simply copying database tables will not preserve future submitted CVs. The scripts currently target this installation and are not a general machine migration tool.

## Database configuration

- Bound to loopback on the configured port (3307 for new setups, existing 3306 installations retained); MySQL X protocol is disabled.
- InnoDB buffer pool: 128 MB; maximum connections: 20; performance schema disabled.
- Development databases: `smarthire` and `smarthire_test`, using `utf8mb4`.
- Separate generated root and `smarthire` passwords. The development user has privileges on these two databases rather than global server administration.
- Application startup creates missing tables; explicit `scripts/run.ps1 -SeedDemo` adds fictional accounts/catalog/jobs. Repeat seeding preserves existing records.
- `configure-mysql.ps1` retains an already initialized data directory rather than resetting it. Do not delete database files to resolve a configuration error.

## Cached Java dependencies

| Library | Version |
| --- | --- |
| PDFBox | 3.0.8 |
| POI OOXML | 6.0.0 |
| MySQL Connector/J | 26.10.0 |
| JFreeChart | 1.5.6 |
| JUnit Jupiter | 6.1.3 |
| Log4j Core runtime | 2.26.1 |

Compiler, test, dependency, and default Maven lifecycle plugin dependencies were also cached. OpenNLP remains conditional on a useful extraction task, as agreed. Maven will manage these libraries; no manual JAR copying or standalone JUnit installation is needed.

Reproduce the setup dependency/smoke check with:

```powershell
.\scripts\cache-dependencies.ps1
```

This downloads missing artifacts if necessary. The checked dependency set also resolved successfully using Maven's offline mode. Different dependencies or plugins added during implementation may still need an initial download.

## Verification completed

- JDK runtime/compiler version checks and Maven startup under the E-drive JDK.
- SHA-256 verification of the Temurin archive, SHA-512 verification of Apache archives, and published MySQL archive checksums. Oracle signatures on MySQL's server binary and Workbench's MSI were valid.
- Workbench `--version` exited successfully.
- NetBeans loaded under JDK 21 and printed its module list in noninteractive mode. That helper process was closed after listing; this was not a visual GUI test. Its launcher emitted cross-platform package warnings.
- MySQL initialization, application-user connection, clean shutdown/restart, and loopback/buffer-pool settings checked.
- A Java 21 smoke program compiled and loaded PDFBox, POI, and JFreeChart, then connected through Connector/J to MySQL 8.4.13.
- Maven offline dependency resolution passed for the cached setup project.

These checks cover the development tools. Application and database test results are recorded in [implementation status](implementation-status.md); interactive Swing acceptance remains pending.

## Official download sources

- [Eclipse Temurin JDK 21](https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64)
- [Apache Maven](https://maven.apache.org/download.cgi)
- [Apache NetBeans 31](https://netbeans.apache.org/front/main/download/nb31/)
- [MySQL 8.4 Community Server](https://dev.mysql.com/downloads/mysql/8.4.html)
- [MySQL Workbench](https://dev.mysql.com/downloads/workbench/)

Exact archive URLs and checksums are recorded locally in `.tools\download-manifest.json`. The installation script uses small ranged transfers because full-size downloads stalled in this environment.
