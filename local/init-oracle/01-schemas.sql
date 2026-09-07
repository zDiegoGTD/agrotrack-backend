-- Un esquema por microservicio dentro de una sola instancia Oracle.
-- El aislamiento por esquema cumple "una DB por servicio" a nivel lógico
-- sin pagar 2 GB de RAM por cada instancia.
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER agro_deliveries IDENTIFIED BY agro_deliveries QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE TO agro_deliveries;

CREATE USER agro_catalog IDENTIFIED BY agro_catalog QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE TO agro_catalog;

CREATE USER agro_audit IDENTIFIED BY agro_audit QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE TO agro_audit;

CREATE USER agro_report IDENTIFIED BY agro_report QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE TO agro_report;
