-- Una base y un rol por microservicio dentro de una sola instancia (D1).
-- Cada servicio solo conoce las credenciales de su base: aislamiento real,
-- sin pagar una instancia por servicio.
CREATE ROLE agro_deliveries LOGIN PASSWORD 'agro_deliveries';
CREATE DATABASE agro_deliveries OWNER agro_deliveries;

CREATE ROLE agro_catalog LOGIN PASSWORD 'agro_catalog';
CREATE DATABASE agro_catalog OWNER agro_catalog;

CREATE ROLE agro_audit LOGIN PASSWORD 'agro_audit';
CREATE DATABASE agro_audit OWNER agro_audit;

CREATE ROLE agro_report LOGIN PASSWORD 'agro_report';
CREATE DATABASE agro_report OWNER agro_report;
