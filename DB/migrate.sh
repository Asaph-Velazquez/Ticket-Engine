#!/bin/sh
set -eu

export PGPASSWORD="$(cat /run/secrets/postgres-password)"
application_password="$(cat /run/secrets/application-password)"
export PGCONNECT_TIMEOUT=10

psql -X --quiet --set=ON_ERROR_STOP=1 --set=app_password="$application_password" --set=db_name="$PGDATABASE" <<'SQL'
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('ticket_engine_schema_migration'));
SELECT 'CREATE ROLE ticket_engine_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticket_engine_app')
\gexec
ALTER ROLE ticket_engine_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO ticket_engine_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ticket_engine_app;
CREATE TABLE IF NOT EXISTS public.schema_migrations (
    version text PRIMARY KEY,
    checksum char(64) NOT NULL,
    installed_at timestamptz NOT NULL DEFAULT now()
);
REVOKE ALL ON public.schema_migrations FROM PUBLIC, ticket_engine_app;
COMMIT;
SQL
unset application_password

found=false
for migration in /migration/migrations/*.sql; do
    [ -f "$migration" ] || continue
    found=true
    version="$(basename "$migration" .sql)"
    case "$version" in
        *[!a-zA-Z0-9_-]*|'') echo 'Nombre de migración inválido.' >&2; exit 1 ;;
    esac
    checksum="$(sha256sum "$migration" | cut -d ' ' -f 1)"
    psql -X --quiet --set=ON_ERROR_STOP=1 --set=version="$version" --set=checksum="$checksum" --set=migration_file="$migration" <<'SQL'
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('ticket_engine_schema_migration'));
SELECT EXISTS(SELECT 1 FROM public.schema_migrations WHERE version = :'version') AS already_installed
\gset
\if :already_installed
SELECT checksum = :'checksum' AS checksum_matches FROM public.schema_migrations WHERE version = :'version'
\gset
\if :checksum_matches
\echo Migración :version ya aplicada.
\else
DO $mismatch$ BEGIN RAISE EXCEPTION 'La migración aplicada cambió; crear una nueva versión'; END $mismatch$;
\endif
\else
\i :migration_file
INSERT INTO public.schema_migrations(version, checksum) VALUES (:'version', :'checksum');
\echo Migración :version aplicada.
\endif
COMMIT;
SQL
done
if [ "$found" != true ]; then
    echo 'No hay migraciones SQL disponibles; no se iniciará la aplicación.' >&2
    exit 1
fi
unset PGPASSWORD
