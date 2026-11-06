# SmartHire demo accounts

The demo seed creates these eight fictional accounts in the configured database.

| Role | Name | Email | Sample CV |
| --- | --- | --- | --- |
| Admin | Farhana Rahman | `admin@smarthire.example` | |
| Company | Dhaka Software Demo | `company@smarthire.example` | |
| Company | Buriganga Software Demo | `company.buriganga@smarthire.example` | |
| Company | Padma Bridge Services Demo | `company.padmabridge@smarthire.example` | |
| Student | Arafat Hasan | `student@smarthire.example` | [Eligible CV](demo-cv-eligible.pdf) |
| Student | Nusrat Jahan | `student.nusrat@smarthire.example` | [Skill-gap CV](demo-cv-skill-gap.pdf) |
| Student | Tanvir Ahmed | `student.tanvir@smarthire.example` | [Mandatory-gap CV](demo-cv-mandatory-gap.pdf) |
| Student | Sadia Rahman | `student.sadia@smarthire.example` | No dedicated sample |

## Passwords

- **Local MySQL.** All eight accounts use `SmartHireDemo!21`.
- **Aiven.** Open `.local/aiven-demo-accounts.properties` from the project root. Use `adminPassword` for Farhana and `demoPassword` for the companies and students. Git ignores the password file.

## Sample CVs

Sign in as the matching student and upload the PDF through **My CV**. Review and confirm the upload, then sign in as the admin and approve that exact version before applying. The sample labels describe their results against the controlled posting in the [demo guide](https://github.com/Eti-24/SmartHire/blob/v0.1.0/docs/demo-guide.md#prepare-the-assessment-posting).
