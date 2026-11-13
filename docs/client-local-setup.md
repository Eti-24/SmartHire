# Client local setup and NetBeans

Use the **source project** containing `pom.xml`, `src`, `scripts`, `Client-Setup.cmd`, `Client-Repair.cmd` and `Start-SmartHire-NetBeans.cmd`. Download it using **Code > Download ZIP** on GitHub or clone the repository. The small application-only release ZIP uses the manual database setup in its bundled README.

## Before the first setup

Keep your existing XAMPP and NetBeans installation. You do not need the password of an existing MySQL server or a Workbench connection. SmartHire creates its own loopback-only portable MySQL server with private generated credentials.

Install PowerShell **7 x64** and the Microsoft Visual C++ x64 runtime described in the root README. Windows PowerShell 5.1 alone cannot run the source setup. Internet is needed for the initial Java 21, Maven, MySQL and dependency downloads. NetBeans must already be installed; setup does not replace it. Use a normal local folder with enough free disk space, outside OneDrive and outside junctions or symbolic links. Extract the project before running it.

## First use

1. Double-click **Client-Setup.cmd** in the project root. Keep the console open until it reports completion. This installs the project tools, creates a dedicated MySQL data directory, builds SmartHire and inserts the initial fictional demo data.
2. Double-click **Start-SmartHire-NetBeans.cmd**. It starts the dedicated database and opens your existing NetBeans using project Java 21. If the IDE cannot be found, select `netbeans64.exe` (or `netbeans.exe`) in your NetBeans installation's `bin` folder. This choice is saved privately for later launches.
3. The first time NetBeans opens, choose **File > Open Project**, select the extracted folder containing `pom.xml`, and open it. Select **Run > Set Main Project > SmartHire** if needed. Use **Run Project (F6)**. If asked, choose `smarthire.Main` as the main class. Keep the working directory set to the project root. NetBeans may download its run plugin the first time.
4. Use the fictional application accounts listed in `demo data`. These application logins are separate from MySQL credentials.

After a PC restart, repeat only step 2, then Run Project in NetBeans. The launcher uses your installed NetBeans' normal settings/profile, which remembers the opened project. It does not force a second SmartHire-specific IDE profile. If NetBeans is already running, the request is handed to that instance; check its taskbar window. Close an already running SmartHire before running it again; its document store allows only one app process.

Ordinary setup reruns retain existing accounts, records, uploads, generated passwords and configured port. They build the app and run the shared readiness check. An existing populated database is not automatically reseeded. Do not share `.local`, `.tools`, or disposable test copies: they contain private configuration.

## Check and repair without resetting

Close SmartHire and double-click **Client-Repair.cmd**. After it reports ready, use **Start-SmartHire-NetBeans.cmd** and Run Project. No MySQL password needs to be typed. Setup, the source run script and the NetBeans launcher also perform this readiness check automatically. A database connection error in the app points to the repair command; running F6 directly does not start MySQL by itself.

| Problem | Recovery behaviour |
| --- | --- |
| PC restarted; dedicated server stopped | Starts the project server and waits for its verified connection. |
| Main database deleted or new database empty | Creates the missing database/schema and initial demo if no old managed CV/circular files remain. This creates a fresh demo; it cannot reconstruct deleted history. |
| Existing populated database | Applies normal additive schema initialization, retains records and application login passwords, and skips automatic demo seeding. |
| Missing/corrupt `database.json` | Recovers generated app credentials from private `mysql-app.ini`, then regenerates app configuration. |
| Incorrect app properties or stale app port | Rewrites generated configuration using the port of the verified project server. |
| Dedicated generated app account or grants damaged | Restores that MySQL app account and privileges scoped to the two SmartHire schemas, using the surviving private admin credentials. Student/company/admin application login passwords are separate and unchanged. |
| XAMPP/another server on the port | Refuses the foreign endpoint or reports occupied port; does not stop it or change its password. Choose a free port during fresh setup. |
| Both app credential copies missing, private admin credentials lost, or unknown custom configuration | Stops with a specific explanation; restore private configuration from backup or use an explicitly confirmed demo reset where supported. Never guesses or resets another server's password. |
| Missing/empty database with old managed CV/circular files, or existing tables without a users table | Stops instead of guessing. Restore the matching SQL backup before opening the app, or deliberately choose demo reset. This protects retained files from normal orphan-file cleanup. |

The generated `.local/readiness-status.txt` records CHECKING or READY with a timestamp and contains no credentials. A failed check prevents the NetBeans launcher from opening the IDE. Do not use reset merely because a repair cannot safely infer the previous state.

These commands apply to the source project. The application-only ZIP does not contain the portable server or source repair scripts; follow that ZIP's setup guide. Cloud configuration is outside this repair workflow.

## Restore the initial demo

Close SmartHire and stop its run in NetBeans. Export anything you need first. Open a terminal in the project root and run:

```powershell
.\Client-Setup.cmd --reset
```

Read the warning, then type **RESET** exactly. Enter alone cancels. The reset stops only the verified project server, deletes its dedicated `.local/mysql-data`, both SmartHire schemas, local managed CV/circular files and generated local server credentials, then creates a fresh server and repopulates the initial demo. Custom records and uploads are permanently lost. XAMPP, cloud configuration/cloud documents, project source, demo fixture files and the saved NetBeans path are retained.

Reset refuses an unmarked older installation, a moved project's ownership record, linked paths, an unverified occupied port, or files still locked by the server. Do not bypass these checks by deleting ownership/configuration files. Normal setup can continue using an older installation without claiming reset ownership.

## If setup or startup fails

- **PowerShell 7 missing:** install it, then retry the CMD file. Do not paste the scripts into Windows PowerShell 5.1.
- **Port 3307 unavailable:** leave XAMPP alone. For a fresh installation use PowerShell 7 in the project root: `./scripts/client-setup.ps1 -Port 3308`. A setup rerun preserves an already configured port. Changing an app properties file does not move a server to another port.
- **NetBeans selection wrong:** delete only `.local/netbeans-path.txt` and relaunch, or run `./scripts/start-client-netbeans.ps1 -NetBeansPath 'C:\path\to\netbeans64.exe'`.
- **CMD finishes but no new NetBeans window appears:** the normal existing IDE may have received the request. Check its taskbar window. The launcher reports immediate failures and treats NetBeans' successful existing-instance handoff as success. It no longer uses the old `.tools/client-netbeans-user` profile, which could be held by a background instance. Do not delete active profile locks or force-close an IDE containing unsaved work.
- **MySQL does not start:** inspect `.local/mysql-error.log`. Do not reinstall Workbench or try to recover an unrelated MySQL password.
- **Application cannot connect:** close it and run Client-Repair.cmd. Launch using Start-SmartHire-NetBeans.cmd after readiness succeeds. Confirm NetBeans runs this project from its root directory.
- **Download/build interrupted:** rerun ordinary setup. An incomplete database initialization may require explicit reset; retain the error and local log for diagnosis.

The setup rehearsal tests a separate server beside another running MySQL and includes paths with spaces. Actual XAMPP coexistence and physical Windows 10/NetBeans interaction still need a check on the client's PC.

## Developer verification

`scripts/test-client-setup.ps1` creates an isolated source copy under `target`, starts a separate server (3307 by default), exercises setup/reset/restart and damaged-installation recovery, and shuts that server down. Failure cases include deleted main schema, lost app config, broken app account/grants, old CV bytes, another server and an occupied port. Requires cached tools/dependencies, a running working-project server for the foreign-endpoint test, and a local NetBeans binary for the path check. It retains the disposable copy with private credentials for inspection; never distribute it. It does not reset the working project's database.

`scripts/test-netbeans-launch.ps1` separately checks the real launch script against a process stub: readiness first, normal installed profile, quoted paths with spaces, saved executable path, running/zero/CONNECTED handoff outcomes, failure reporting and CheckOnly. The CONNECTED status is defined as -252 in [Apache NetBeans CLIHandler](https://github.com/apache/netbeans/blob/master/platform/o.n.bootstrap/src/org/netbeans/CLIHandler.java). The actual root CMD was also run on the development PC and the visible IDE window was inspected; cold-start/client Windows 10 acceptance remains separate.
