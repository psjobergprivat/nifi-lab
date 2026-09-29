# NiFi plus what the lab scenarios need: the PostgreSQL JDBC driver (scenario 10) and an inbox folder (scenario 09).
FROM docker.io/apache/nifi:2.12.0
ADD --chown=nifi:nifi \
    https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar \
    /opt/nifi/drivers/postgresql.jar
# Scenario 09 writes files here and polls the folder with ListFile.
RUN mkdir -p /opt/nifi/lab-inbox
