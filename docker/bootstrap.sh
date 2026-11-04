#!/bin/sh
set -eu
umask 077

mkdir -p /secrets /keys /test-keys
for name in postgres-password application-password admin-token payment-hmac-secret; do
    path="/secrets/$name"
    if [ ! -e "$path" ]; then
        temporary="$(mktemp /secrets/.secret.XXXXXX)"
        openssl rand -hex 32 | tr -d '\n' > "$temporary"
        [ "$(wc -c < "$temporary")" -eq 64 ] || exit 1
        mv "$temporary" "$path"
    fi
    if [ ! -s "$path" ]; then
        echo "El secreto $name existe pero está vacío; se requiere recuperación manual." >&2
        exit 1
    fi
    # Compose file-backed secrets need to be readable by the non-root application.
    # Local operators must protect the host directory with their filesystem ACLs.
    chmod 644 "$path"
done

ensure_keys() {
key_dir="$1"
if [ -e "$key_dir/public.pem" ] && [ ! -e "$key_dir/private.pem" ]; then
    echo 'Falta la llave privada de una pareja existente; no se generará otra.' >&2
    exit 1
fi
if [ ! -e "$key_dir/private.pem" ]; then
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:prime256v1 -out "$key_dir/.private.tmp"
    mv "$key_dir/.private.tmp" "$key_dir/private.pem"
fi
if [ ! -s "$key_dir/private.pem" ]; then
    echo 'La llave privada está vacía; se requiere restaurar el respaldo.' >&2
    exit 1
fi
if [ ! -e "$key_dir/public.pem" ]; then
    openssl pkey -in "$key_dir/private.pem" -pubout -out "$key_dir/.public.tmp"
    mv "$key_dir/.public.tmp" "$key_dir/public.pem"
fi
openssl pkey -in "$key_dir/private.pem" -check -noout >/dev/null 2>&1
openssl pkey -in "$key_dir/private.pem" -pubout -out "$key_dir/.derived-public.tmp"
if ! cmp -s "$key_dir/.derived-public.tmp" "$key_dir/public.pem"; then
    rm -f "$key_dir/.derived-public.tmp"
    echo 'La pareja de llaves existente no coincide; no se reemplazará.' >&2
    exit 1
fi
rm -f "$key_dir/.derived-public.tmp"
chown 10001:10001 "$key_dir" "$key_dir/private.pem" "$key_dir/public.pem"
chmod 500 "$key_dir"
chmod 400 "$key_dir/private.pem"
chmod 444 "$key_dir/public.pem"
}

ensure_keys /keys
ensure_keys /test-keys
if cmp -s /keys/public.pem /test-keys/public.pem; then
    echo 'Las llaves de aplicación y pruebas deben ser distintas.' >&2
    exit 1
fi
echo 'Secretos y parejas P-256 independientes disponibles; no se muestran sus valores.'
