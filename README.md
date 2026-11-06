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
- The database diagram is available as [HTML](docs/database/er-diagram.html), [SVG](docs/database/er-diagram.svg), and [Mermaid source](docs/database/er-diagram.mmd). The [MySQL schema and migrations](src/main/resources/db) are in the app resources. [Implementation status](docs/implementation-status.md) records test results and manual checks.

## Features

### Students

| Feature | What it does |
| --- | --- |
| CV templates and uploads | Download an editable Word template or upload your own PDF or DOCX. SmartHire extracts the text and suggests qualifications for you to review. |
| CV review and version history | Correct skills, education, experience, projects, and certifications before confirming a version for admin review. Open older versions and download the original files. |
| Job search | Search by title and category, and narrow results by location, salary range, or closing date. |
| Saved jobs | Bookmark postings and see when they fill or become unavailable. If you already applied, open the application from the saved entry. |
| Saved searches | Save, rerun, edit, pause, or remove a set of filters. Active searches can send an inbox alert when a newly approved job matches. |
| Job recommendations | Rank open jobs using your reviewed CV. Open a match to see how each qualification affected the score and which mandatory requirements you still need to meet. |
| CV improvement checklist | See which qualifications are missing for a particular job and go back to My CV to update your review. |
| Job comparison | Compare two jobs side by side using the same CV. A separate text similarity figure shows how much the CV and job descriptions overlap. |
| Applications | Apply once per job with an approved CV version if you meet the mandatory requirements. Track the result, review the original submission, or withdraw while the application is active. |
| Hiring history | See postings that have filled their vacancies. Your own application result stays in My applications. |

### Companies

| Feature | What it does |
| --- | --- |
| Job posting | Set the description, category, location, salary, vacancies, and an optional application deadline with a time zone. Submit the job for approval, edit and resubmit it, or close it. |
| Structured requirements | Specify skills, education, experience, project evidence, and certifications. Mark any requirement as mandatory to make it a condition for applying. |
| Job circulars | Download a Word template or attach a PDF or DOCX circular. View its extracted text, download the original, and review suggested requirements from the circular or job description. |
| Applicant review | Filter and rank applicants for your own postings. Open the qualifications, contact details, and assessment saved at submission, and download the original CV. |
| Hiring decisions and vacancy tracking | Shortlist, reject, or hire applicants. Filling the last vacancy removes the job from search and stops new applications. Company hiring history links back to the successful applications. |
| Recruiter notes and scorecards | Keep private notes on an application and record a scorecard after a confirmed interview has ended. |

### Admins

| Feature | What it does |
| --- | --- |
| Company verification | Verify companies so their approved jobs can appear in search. Removing verification hides their jobs from students. |
| Job moderation | Approve or reject job postings. Edits to a posting return it to review, and its requirements are frozen once it has an application. |
| CV approval | Review the original document and confirmed qualifications, then approve or reject that specific CV version. |
| Categories and skills | Maintain job categories, technical and soft skills, and skill aliases used in job postings and CV reviews. |
| Matching weights | Activate a scoring policy for current recommendations and applicant rankings. Each application retains the score and policy used when it was submitted. |
| Account access | Enable or disable student and company accounts while retaining their history. |
| Application monitoring | Filter applications by job, company, student, or status and inspect their original assessments in a read-only view. |
| Data export and erasure | Export data for a selected student, company, job, or CV version, or permanently erase the selected record and its associated data. |
| Activity log | Look up recorded admin actions such as moderation decisions, exports, and erasures. |

### Shared features

| Feature | What it does |
| --- | --- |
| Accounts and profiles | Students and companies can register. Each role signs in to its own workspace, where users can update profile details, change passwords, and generate recovery codes for a password reset. |
| Dashboards | See counts relevant to your role and a chart of application statuses. |
| Notifications | Check the inbox for events such as approvals, application updates, and saved-search matches. SmartHire remembers which notices you've read. |
| Interview scheduling | Companies propose interview times for shortlisted applicants and can cancel interviews. Students can accept or request a different time. Both sides can see the schedule and its revisions. |
| Application timeline | Students and companies can review the submission, recorded status changes, and interview updates in order. |
| Recruitment reports | Companies and admins can filter applications and review conversion rates, average times between recorded events, and current vacancy counts. |
| Local and cloud database setup | Run SmartHire with local MySQL or use the separate Windows launcher for an Aiven MySQL database. |
| Demo data | Load fictional accounts and jobs, then use the included CVs and job circular to try the recruitment workflow. |

## How job matching works

### What goes into a match

When you upload a PDF or Word CV, SmartHire extracts text and suggests qualifications. You can correct those suggestions and add missing evidence in **My CV**. Confirming the review saves a CV version. **Job matches** uses the latest confirmed version, including one still waiting for admin approval. If you haven't confirmed a review, the app asks you to do that first.

The score uses the confirmed qualifications and the structured requirements on the job posting. The company sets those requirements when it creates the posting. Recommendations consider approved jobs that are still accepting applications from active, verified companies. A closing deadline or a filled final vacancy removes the posting from recommendations.

### How the percentage is calculated

Each category earns a fraction from 0 to 1. The default scoring policy is `default-v1`.

| Category | Default weight | How it earns credit |
| --- | ---: | --- |
| Skills | 40 | Add the weights of the listed skills the student has, then divide by the total weight of the listed skills. A skill marked Required counts twice as much as one marked Optional. Technical and soft skills use the same rule. The skill catalog maps recognized aliases to the same skill. |
| Education | 20 | Give full credit if the confirmed degree meets the minimum and the field matches when one is specified. The degree order is Secondary, Diploma, Bachelor, Master, Doctorate. Fields are compared after normalizing Unicode, case, and spaces. Abbreviations such as CS are distinct from Computer Science. |
| Experience | 15 | Divide the number of relevant months by the minimum requested, up to full credit. A work entry counts if at least one of its skill tags matches the job's experience tags. The app counts both end months and counts each calendar month once. For example, January through April plus March through June counts as six months. For ongoing work, the end month is fixed when the CV review is confirmed. |
| Projects | 15 | Divide matched project skills by the number of project skills the job lists. A skill has to appear on a confirmed project to earn credit here. |
| Certifications | 10 | Divide matched certifications by the number the job lists. Names must match after normalizing Unicode, case, and spaces. |

The app divides each participating category's weight by the sum of the weights of categories with scored requirements. A job with only skills and education therefore uses weights of `40 / 60` and `20 / 60`. A zero-month experience requirement and experience tags alone earn no points. Multiply each category fraction by its adjusted weight and by 100, then add the resulting percentage points. The app keeps the unrounded score for sorting and displays two decimal places. Admins can activate new weights. A policy must give each affected job at least one scored category with a positive weight.

### Eligibility and a worked example

The company can mark individual requirements **Mandatory**. Every mandatory check has to pass for the student to be eligible. The **Required** skill priority controls scoring weight, while the **Mandatory** checkbox controls eligibility. Degree level and field can each have their own mandatory check.

In the demo walkthrough, a job lists Java as Optional and Mandatory. SQL, communication, and teamwork are Required, so each has a skill weight of two. The posting also asks for a Bachelor's degree. A student with the degree and all three Required skills earns `6 / 7` for skills and full credit for education. The final score is `100 × [(6 / 7 × 40 / 60) + (1 × 20 / 60)] = 90.48%` after rounding. The student is ineligible because Java is missing. The [demo walkthrough](docs/demo-guide.md#student-journey-and-expected-results) has the sample CVs and two more outcomes for that posting.

### Recommendations, comparisons, and applications

- **Job matches** puts eligible jobs first, then sorts each group by descending score and uses job ID to break ties. The app recalculates when you refresh or open a match. **Why this match?** shows what matched and what's missing. The **Score details** tab includes category weights, points, counted experience, and mandatory checks. **What to improve** lists gaps you can review in My CV.
- **Compare two jobs** uses the same confirmed CV and policy for both postings. It also compares the extracted CV text with each job description using TF-IDF and cosine similarity. TF-IDF gives more weight to terms that occur often in one document and in fewer documents across the comparison. Cosine similarity measures the overlap between the resulting term vectors. Empty text gives zero similarity. The similarity figure is displayed separately from the score and eligibility.
- **Apply** rechecks the job's availability, approval of the exact CV version, and the mandatory requirements. A student can apply once per job. The application keeps the submitted CV and contact details along with the score, explanation, and policy used at submission.
- **Applicant rankings** use the current policy to score each applicant's submitted qualifications against the job's frozen requirements. Within a posting, the highest score comes first. Ties go to the earlier submission, then to application ID. A new CV or a change to the weights leaves the original assessment in the application history.
