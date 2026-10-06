-- Simulates the backend's footprint in the shared database: its own tables, its own Flyway history in public,
-- and another schema. The pipeline's Flyway run must leave all of it untouched.
CREATE TABLE public.app_user (id int PRIMARY KEY, email text NOT NULL);
INSERT INTO public.app_user VALUES (1, 'someone@example.com');

CREATE TABLE public.flyway_schema_history (
    installed_rank int PRIMARY KEY, version text, description text NOT NULL, type text NOT NULL,
    script text NOT NULL, checksum int, installed_by text NOT NULL, installed_on timestamp NOT NULL DEFAULT now(),
    execution_time int NOT NULL, success boolean NOT NULL);
INSERT INTO public.flyway_schema_history
VALUES (1, '1', 'backend init', 'SQL', 'V1__backend_init.sql', 123, 'backend', now(), 5, true);

CREATE SCHEMA backend_other;
CREATE TABLE backend_other.thing (x int);
INSERT INTO backend_other.thing VALUES (42);
