# Additional JDBC Drivers

The connector image contains exactly two JDBC drivers:

| Database | Driver | License |
|---|---|---|
| Microsoft SQL Server | `com.microsoft.sqlserver:mssql-jdbc` | MIT |
| PostgreSQL | `org.postgresql:postgresql` | BSD-2-Clause |

Both licenses are compatible with the connector's Apache License 2.0, so these drivers can be shipped with the source code and the container image.

## Why other drivers are not included

The connector and everything it distributes must be licensed under Apache-2.0 or a compatible permissive license (for example MIT or BSD). The JDBC drivers of several common databases do not meet this requirement:

| Database | Driver | License | Problem |
|---|---|---|---|
| MySQL | MySQL Connector/J | GPL-2.0 with Universal FOSS Exception | Copyleft; bundling it would impose GPL terms on the distribution |
| MariaDB | MariaDB Connector/J | LGPL-2.1 | Weak copyleft; not compatible with the connector's permissive-only rule |
| Oracle | Oracle JDBC (ojdbc) | Oracle Free Use Terms and Conditions | Proprietary license, redistribution restricted |
| IBM Db2 | IBM Data Server Driver (jcc) | IBM proprietary license | Proprietary license, redistribution restricted |

These drivers are therefore never part of this repository, the Maven build, or the image. An operator who needs one obtains it separately, accepts its license, and mounts it at runtime. The connector itself is unchanged; it only loads the JAR from this directory.

Check the license of any other driver before adding it. A driver that is Apache-2.0, MIT, or BSD licensed may also be added to the `pom.xml` instead, if it should become part of the distribution.

## Adding a driver

1. Download the driver JAR (including any non-optional dependencies it needs) from the vendor and place it in this directory, for example `drivers/mariadb-java-client-3.5.6.jar`. JAR files here are ignored by Git and by the Docker build context, so they cannot be committed or baked into the image by accident.
2. Set the connection in `.env`. A full JDBC URL is required because the connector only builds URLs for SQL Server and PostgreSQL itself:

   ```dotenv
   DB_DIALECT=OTHER
   DB_URL=jdbc:mariadb://db.example.org:3306/clinic?sslMode=verify-full
   # Only needed when the driver does not register itself through java.sql.Driver service loading
   DB_DRIVER_CLASS_NAME=org.mariadb.jdbc.Driver
   ```

   `DB_DIALECT=OTHER` uses the SQL standard `FETCH FIRST n ROWS ONLY` clause to read rows page by page (supported for example by MariaDB 10.6+, Oracle 12c+, and Db2). Identifiers are quoted with the quote string the driver reports.

3. Start the connector. Compose mounts this directory read-only at `/app/drivers`, and the image starts the application with `loader.path=/app/drivers`, so every JAR in it is put on the class path. Outside Docker, run the packaged application with the same option:

   ```sh
   java -Dloader.path=drivers -jar target/trustdeck-sql-connector-1.0.0.jar
   ```

## Databases without `FETCH FIRST`

MySQL and MariaDB before 10.6 only understand `LIMIT n`. Set `DB_DIALECT=OTHER_LIMIT` for these databases; the connector then appends `LIMIT n` instead of `FETCH FIRST n ROWS ONLY`. All other parts of the generated statements are standard SQL.
