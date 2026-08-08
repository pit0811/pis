# Agenda Educativa Web en Java

Versión web del proyecto académico. Java 17 procesa las solicitudes y genera
la interfaz HTML; el diseño está en CSS y no se utiliza JavaScript.

## Funciones

- Agregar, editar, eliminar y completar actividades.
- Ordenamiento por selección y burbuja.
- Búsqueda binaria por nombre.
- Matriz semanal `Horario[5][6]`.
- Recursividad, vector, nodos, lista simple, lista circular y árbol binario.
- Persistencia sencilla en archivos TXT.

## Ejecutar localmente

```powershell
javac --release 17 --add-modules jdk.httpserver AgendaWeb.java
java --add-modules jdk.httpserver AgendaWeb
```

Abre `http://localhost:8080`.

## Publicar en Render

1. Inicia sesión en Render con GitHub.
2. Selecciona `New > Blueprint`.
3. Conecta este repositorio.
4. Render leerá `render.yaml`, construirá Docker y entregará el enlace público.

En un alojamiento gratuito los archivos TXT pueden reiniciarse cuando el
servicio se recrea. Esta persistencia es adecuada para una demostración
académica; una aplicación real utilizaría una base de datos.
