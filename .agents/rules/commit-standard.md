# Estándar de commits del repositorio

Todos los commits de este repositorio deben cumplir estas reglas.

## Formato

```text
tipo(alcance): descripción breve en español
```

La descripción debe ser específica, clara y estar escrita en español. Debe comenzar con un verbo en infinitivo o expresar claramente la acción realizada.

## Tipos permitidos

- `feat`: agregar una funcionalidad nueva.
- `fix`: corregir un error.
- `refactor`: modificar la estructura sin cambiar el comportamiento esperado.
- `test`: agregar o modificar pruebas.
- `docs`: modificar documentación.
- `style`: aplicar cambios de formato sin alterar la lógica.
- `build`: modificar dependencias o configuración de compilación.
- `ci`: modificar integración o despliegue continuo.
- `chore`: realizar tareas de mantenimiento.
- `perf`: mejorar el rendimiento.
- `security`: aplicar una mejora o corrección de seguridad.

## Reglas obligatorias

1. El mensaje debe estar escrito en español.
2. El mensaje debe describir exactamente el cambio realizado.
3. La primera línea debe tener como máximo 72 caracteres cuando sea posible.
4. No usar mensajes genéricos como `cambios`, `actualización`, `arreglos` o `avance`.
5. No incluir varios cambios independientes en un mismo commit.
6. Usar un alcance relacionado con el módulo o componente afectado.
7. No incluir secretos, contraseñas, llaves privadas ni archivos `.env`.
8. Antes de hacer commit, revisar que las pruebas y validaciones correspondientes pasen.

## Ejemplos válidos

```text
feat(emision): agregar generación de identificador único del boleto
fix(crypto): corregir verificación de firmas ECDSA
test(crypto): cubrir firma con curva secp256r1
docs(api): documentar endpoint de verificación de boletos
build(gradle): configurar interoperabilidad entre Java y Kotlin
security(llaves): impedir exposición de la llave privada
```

## Ejemplos inválidos

```text
 cambios
 update
 fixed bug
 feat: todo
 avance del proyecto
```

## Cambios incompatibles

Si un cambio rompe la compatibilidad con otros módulos, agregar `!` después del alcance:

```text
feat(api)!: cambiar el formato de respuesta de verificación
```

En ese caso, el cuerpo del commit debe explicar qué cambió y cómo deben adaptarse los demás módulos.
