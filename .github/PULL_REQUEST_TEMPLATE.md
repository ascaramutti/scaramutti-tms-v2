<!--
Título del PR: en inglés, una sola intención, igual al header del commit principal.
Esta descripción es para quien lee el CAMBIO: qué hace y su porqué en una línea.
No van: notas de coordinación, IDs de planificación, rutas fuera del repositorio,
fundamentos defensivos, atribuciones de herramientas ni referencias a documentos que
no estén en este repositorio. El nombre de la rama también es público.
-->

## Qué cambia

## Por qué

<!-- Una línea. El fundamento largo vive en la documentación del proyecto, no acá. -->

## Cómo se probó

- [ ] Suite completa en verde en local (no solo lo tocado)
- [ ] Verificación local a mano del dueño con la aplicación levantada
- [ ] Casos relevantes cubiertos: 4xx del contrato, 403 por rol, límites

## Documentación

- [ ] Contrato (`api/openapi.yaml` y spec runtime) si cambió validación, forma o códigos de error
- [ ] Documentación de la unidad: decisiones, hallazgos de revisión
- [ ] Registrar el cambio en el historial del proyecto: **después** del merge, no acá

## Smoke en staging

Pendiente hasta el merge y el deploy. **El PR está terminado cuando el smoke pasa.**
