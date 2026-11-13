$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'dev-env.ps1')
$project = Split-Path $PSScriptRoot -Parent
$bootstrap = Join-Path $project '.tools\dependency-check'
New-Item -ItemType Directory -Path $bootstrap -Force | Out-Null
@'
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.smarthire.setup</groupId><artifactId>dependency-check</artifactId><version>1.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <dependencies>
    <dependency><groupId>org.apache.pdfbox</groupId><artifactId>pdfbox</artifactId><version>3.0.8</version></dependency>
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>6.0.0</version></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>26.10.0</version></dependency>
    <dependency><groupId>org.jfree</groupId><artifactId>jfreechart</artifactId><version>1.5.6</version></dependency>
    <dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId><version>2.26.1</version><scope>runtime</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.1.3</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.9.0</version></plugin>
  </plugins></build>
</project>
'@ | Set-Content -LiteralPath (Join-Path $bootstrap 'pom.xml') -Encoding utf8NoBOM
& mvn --batch-mode --no-transfer-progress -f (Join-Path $bootstrap 'pom.xml') dependency:go-offline
if ($LASTEXITCODE -ne 0) { throw 'Dependency caching failed.' }
& mvn --batch-mode --no-transfer-progress -f (Join-Path $bootstrap 'pom.xml') dependency:build-classpath '-Dmdep.outputFile=classpath.txt'
if ($LASTEXITCODE -ne 0) { throw 'Dependency classpath generation failed.' }
@'
import java.io.*;
import java.sql.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jfree.data.category.DefaultCategoryDataset;

public class ToolchainCheck {
    public static void main(String[] args) throws Exception {
        try (var pdf = new PDDocument(); var docx = new XWPFDocument()) {
            docx.createParagraph().createRun().setText("SmartHire setup check");
            var chartData = new DefaultCategoryDataset();
            chartData.addValue(1, "setup", "ready");
            System.out.println("JDK " + Runtime.version() + ": PDFBox, POI and JFreeChart loaded.");
        }
        var properties = new Properties();
        try (var input = new FileInputStream(args[0])) { properties.load(input); }
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + properties.getProperty("port", "3306") + "/smarthire?sslMode=DISABLED&allowPublicKeyRetrieval=true", properties);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT VERSION()")) {
            rows.next();
            System.out.println("Connector/J connected to MySQL " + rows.getString(1));
        }
    }
}
'@ | Set-Content -LiteralPath (Join-Path $bootstrap 'ToolchainCheck.java') -Encoding utf8NoBOM
$db = Get-Content -LiteralPath (Join-Path $project '.local\database.json') -Raw | ConvertFrom-Json
"port=$(if ($db.port) { $db.port } else { 3306 })`nuser=$($db.user)`npassword=$($db.password)" | Set-Content -LiteralPath (Join-Path $bootstrap 'database.properties') -Encoding ascii
$classpath = (Get-Content -LiteralPath (Join-Path $bootstrap 'classpath.txt') -Raw).Trim()
& javac --release 21 -proc:none -cp $classpath (Join-Path $bootstrap 'ToolchainCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Java 21 compilation check failed.' }
& java -cp "$bootstrap;$classpath" ToolchainCheck (Join-Path $bootstrap 'database.properties')
if ($LASTEXITCODE -ne 0) { throw 'Java library/JDBC smoke check failed.' }
Write-Output 'Dependencies cached and Java/JDBC smoke check passed. This is not an application build.'
