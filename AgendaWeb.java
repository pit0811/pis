import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Agenda web sin JavaScript: Java genera el HTML y procesa los formularios. */
public class AgendaWeb {
    static final Actividad[] actividades = new Actividad[100];
    static final Horario[][] horarios = new Horario[5][6];
    static final String[] DIAS = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"};
    static final String[] BLOQUES = {"07:00-08:00", "08:00-09:00", "09:00-10:00",
            "10:00-11:00", "11:00-12:00", "12:00-13:00"};
    static final File DATOS = new File("data"), ARCHIVO_A = new File(DATOS, "actividades.txt");
    static final File ARCHIVO_H = new File(DATOS, "horarios.txt");
    static int cantidad;
    static Nodo cabezaLista;
    static NodoCircular cabezaCircular;
    static NodoArbol raizArbol;
    static int pasoCircular;

    public static void main(String[] args) throws Exception {
        cargar();
        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer servidor = HttpServer.create(new InetSocketAddress("0.0.0.0", puerto), 0);
        servidor.createContext("/styles.css", e -> responder(e, 200, "text/css", leerCss()));
        servidor.createContext("/salud", e -> responder(e, 200, "text/plain", "OK"));
        servidor.createContext("/", AgendaWeb::atender);
        servidor.start();
        System.out.println("Agenda iniciada en el puerto " + puerto);
    }

    static void atender(HttpExchange e) throws IOException {
        try {
            String ruta = e.getRequestURI().getPath();
            Map<String, String> p = parametros(e);
            if ("GET".equals(e.getRequestMethod())) {
                String vista = p.getOrDefault("vista", "resumen");
                String mensaje = p.getOrDefault("mensaje", "");
                if (ruta.equals("/recordatorio")) mensaje = siguienteCircular();
                if (ruta.equals("/buscar")) mensaje = buscar(p.getOrDefault("nombre", ""));
                responder(e, 200, "text/html", pagina(vista, mensaje, p));
                return;
            }
            if (!"POST".equals(e.getRequestMethod())) { responder(e, 405, "text/plain", "Método no permitido"); return; }
            String mensaje;
            synchronized (AgendaWeb.class) {
                mensaje = switch (ruta) {
                    case "/actividad/guardar" -> guardarActividad(p);
                    case "/actividad/eliminar" -> eliminarActividad(p);
                    case "/actividad/estado" -> cambiarEstado(p);
                    case "/ordenar/nombre" -> { ordenarSeleccion(); guardarActividades(); yield "Vector ordenado por selección."; }
                    case "/ordenar/fecha" -> { ordenarBurbuja(); guardarActividades(); yield "Vector ordenado por burbuja."; }
                    case "/horario/guardar" -> guardarBloque(p);
                    case "/horario/eliminar" -> eliminarBloque(p);
                    default -> "Operación desconocida.";
                };
            }
            String vista = ruta.startsWith("/horario") ? "horario" : "actividades";
            redirigir(e, "/?vista=" + vista + "&mensaje=" + codificar(mensaje));
        } catch (Exception error) {
            responder(e, 400, "text/html", pagina("resumen", "Error: " + error.getMessage(), Map.of()));
        }
    }

    static String pagina(String vista, String mensaje, Map<String, String> p) {
        reconstruir();
        String contenido = switch (vista) {
            case "actividades" -> vistaActividades(p);
            case "horario" -> vistaHorario();
            case "estructuras" -> vistaEstructuras();
            default -> vistaResumen();
        };
        String aviso = mensaje.isBlank() ? "" : "<div class='aviso'>" + esc(mensaje) + "</div>";
        return """
                <!doctype html><html lang='es'><head><meta charset='UTF-8'>
                <meta name='viewport' content='width=device-width,initial-scale=1'>
                <title>Agenda Educativa</title><link rel='stylesheet' href='/styles.css'></head><body>
                <header><div><h1>Agenda Educativa</h1><p>Proyecto Java · Estructuras de datos</p></div>
                <span>Segundo semestre</span></header>
                <nav><a href='/?vista=resumen'>Resumen</a><a href='/?vista=actividades'>Actividades</a>
                <a href='/?vista=horario'>Horario</a><a href='/?vista=estructuras'>Estructuras utilizadas</a></nav>
                <main>%s%s</main><footer>Java 17 · HTML y CSS · Sin JavaScript</footer></body></html>
                """.formatted(aviso, contenido);
    }

    static String vistaResumen() {
        StringBuilder lista = new StringBuilder(), arbol = new StringBuilder();
        for (Nodo n = cabezaLista; n != null; n = n.siguiente) lista.append("<li>").append(esc(n.a.nombre)).append("</li>");
        inorden(raizArbol, arbol);
        return """
                <section class='metricas'>%s%s%s%s</section>
                <section class='dos-columnas'><article class='tarjeta'><h2>Lista enlazada simple</h2>
                <p>Actividades pendientes conectadas por nodos.</p><ul>%s</ul></article>
                <article class='tarjeta'><h2>Árbol binario de búsqueda</h2>
                <p>Recorrido inorden alfabético.</p><ul>%s</ul></article></section>
                <article class='tarjeta recordatorio'><div><h2>Lista circular</h2>
                <p>El último recordatorio vuelve al primero.</p></div>
                <a class='boton secundario' href='/recordatorio?vista=resumen'>Siguiente recordatorio</a></article>
                """.formatted(metrica("Actividades", cantidad), metrica("Pendientes", contarPendientes(0)),
                metrica("Horas pendientes", String.format("%.1f h", sumarHoras(0))),
                metrica("Bloques ocupados", contarBloques()),
                lista.length() == 0 ? "<li>No hay pendientes.</li>" : lista,
                arbol.length() == 0 ? "<li>El árbol está vacío.</li>" : arbol);
    }

    static String vistaActividades(Map<String, String> p) {
        int editar = entero(p.get("editar"), -1);
        Actividad a = editar >= 0 && editar < cantidad ? actividades[editar] : null;
        StringBuilder filas = new StringBuilder();
        for (int i = 0; i < cantidad; i++) {
            Actividad x = actividades[i];
            filas.append("<tr><td>").append(esc(x.nombre)).append("</td><td>").append(x.fecha)
                    .append("</td><td><span class='prioridad ").append(x.prioridad.toLowerCase()).append("'>")
                    .append(x.prioridad).append("</span></td><td>").append(x.horas).append(" h</td><td>")
                    .append(x.completada ? "Completada" : "Pendiente").append("</td><td class='acciones-tabla'>")
                    .append("<a class='mini' href='/?vista=actividades&editar=").append(i).append("'>Editar</a>")
                    .append(formBoton("/actividad/estado", i, x.completada ? "Reabrir" : "Completar", "mini"))
                    .append(formBoton("/actividad/eliminar", i, "Eliminar", "mini peligro"))
                    .append("</td></tr>");
        }
        return """
                <article class='tarjeta'><h2>%s actividad</h2><form method='post' action='/actividad/guardar' class='formulario'>
                <input type='hidden' name='indice' value='%d'><label>Nombre<input name='nombre' required minlength='2' value='%s'></label>
                <label>Fecha<input type='date' name='fecha' required value='%s'></label>
                <label>Prioridad<select name='prioridad'>%s</select></label>
                <label>Duración<input type='number' name='horas' min='0.5' max='20' step='0.5' value='%s'></label>
                <button>%s</button><a class='boton claro' href='/?vista=actividades'>Limpiar</a></form></article>
                <article class='tarjeta'><div class='barra'><form method='post' action='/ordenar/nombre'><button class='claro'>Selección: nombre</button></form>
                <form method='post' action='/ordenar/fecha'><button class='claro'>Burbuja: fecha</button></form>
                <form method='get' action='/buscar'><input type='hidden' name='vista' value='actividades'>
                <input name='nombre' placeholder='Nombre exacto' required><button>Búsqueda binaria</button></form></div>
                <div class='tabla'><table><thead><tr><th>Nombre</th><th>Fecha</th><th>Prioridad</th><th>Horas</th><th>Estado</th><th>Acciones</th></tr></thead>
                <tbody>%s</tbody></table></div></article>
                """.formatted(a == null ? "Agregar" : "Editar", editar, a == null ? "" : esc(a.nombre),
                a == null ? LocalDate.now() : a.fecha, opciones(a == null ? "Media" : a.prioridad),
                a == null ? "1.0" : a.horas, a == null ? "Agregar" : "Actualizar", filas);
    }

    static String vistaHorario() {
        StringBuilder filas = new StringBuilder();
        for (int b = 0; b < 6; b++) {
            filas.append("<tr><th>").append(BLOQUES[b]).append("</th>");
            for (int d = 0; d < 5; d++) {
                Horario h = horarios[d][b];
                filas.append("<td class='").append(h == null ? "libre" : "ocupado").append("'>");
                if (h == null) filas.append("Disponible");
                else filas.append("<strong>").append(esc(h.materia)).append("</strong>")
                        .append("<form method='post' action='/horario/eliminar'><input type='hidden' name='dia' value='")
                        .append(d).append("'><input type='hidden' name='bloque' value='").append(b)
                        .append("'><button class='enlace-peligro'>Quitar</button></form>");
                filas.append("</td>");
            }
            filas.append("</tr>");
        }
        return """
                <article class='tarjeta'><h2>Agregar bloque académico</h2>
                <form method='post' action='/horario/guardar' class='formulario horario-form'>
                <label>Materia<input name='materia' required minlength='2'></label>
                <label>Día<select name='dia'>%s</select></label><label>Bloque<select name='bloque'>%s</select></label>
                <button>Guardar bloque</button></form></article>
                <article class='tarjeta'><h2>Arreglo bidimensional Horario[5][6]</h2><div class='tabla'>
                <table class='horario'><thead><tr><th>Hora</th>%s</tr></thead><tbody>%s</tbody></table></div></article>
                """.formatted(opcionesIndice(DIAS), opcionesIndice(BLOQUES), encabezados(DIAS), filas);
    }

    static String vistaEstructuras() {
        String[][] temas = {{"Algoritmos recursivos", "Contar pendientes y sumar horas"}, {"Arreglos", "Actividad[100]"},
                {"Vectores", "Vector de actividades"}, {"Búsqueda binaria", "Buscar por nombre"},
                {"Ordenamiento", "Selección y burbuja"}, {"Arreglos bidimensionales", "Horario[5][6]"},
                {"Listas enlazadas", "Pendientes conectadas"}, {"Listas simples", "Último nodo → null"},
                {"Nodos", "Dato y referencia"}, {"Listas circulares", "Último nodo → primero"},
                {"Árbol binario", "Orden alfabético"}};
        StringBuilder filas = new StringBuilder();
        for (String[] tema : temas) filas.append("<tr><td>").append(tema[0]).append("</td><td>").append(tema[1]).append("</td></tr>");
        return """
                <section class='dos-columnas'><article class='tarjeta'><h2>Temas aplicados</h2><table>
                <thead><tr><th>Tema</th><th>Uso en la agenda</th></tr></thead><tbody>%s</tbody></table></article>
                <article class='tarjeta demo'><h2>Demostración actual</h2><p><b>Vector:</b> %d de 100 posiciones</p>
                <p><b>Recursión:</b> %d pendientes y %.1f horas</p><p><b>Matriz:</b> %d de 30 bloques</p>
                <p><b>Lista simple:</b> %d nodos</p><p><b>Lista circular:</b> %s</p>
                <p><b>Árbol binario:</b> altura %d</p></article></section>
                """.formatted(filas, cantidad, contarPendientes(0), sumarHoras(0), contarBloques(),
                contarNodos(cabezaLista), cabezaCircular == null ? "vacía" : "último → primero", altura(raizArbol));
    }

    // Operaciones con el vector.
    static String guardarActividad(Map<String, String> p) {
        String nombre = limpiar(p.get("nombre")), fecha = p.getOrDefault("fecha", "");
        String prioridad = p.getOrDefault("prioridad", "Media");
        double horas = Double.parseDouble(p.getOrDefault("horas", "1"));
        LocalDate.parse(fecha);
        if (nombre.length() < 2 || horas < 0.5 || horas > 20) return "Datos de actividad inválidos.";
        int i = entero(p.get("indice"), -1);
        if (i >= 0 && i < cantidad) {
            boolean estado = actividades[i].completada;
            actividades[i] = new Actividad(nombre, fecha, prioridad, horas, estado);
        } else if (cantidad < actividades.length) actividades[cantidad++] = new Actividad(nombre, fecha, prioridad, horas, false);
        else return "El vector está lleno.";
        guardarActividades();
        return i >= 0 ? "Actividad actualizada." : "Actividad agregada.";
    }

    static String eliminarActividad(Map<String, String> p) {
        int i = entero(p.get("indice"), -1);
        if (i < 0 || i >= cantidad) return "Actividad inexistente.";
        for (; i < cantidad - 1; i++) actividades[i] = actividades[i + 1];
        actividades[--cantidad] = null;
        guardarActividades();
        return "Actividad eliminada.";
    }

    static String cambiarEstado(Map<String, String> p) {
        int i = entero(p.get("indice"), -1);
        if (i < 0 || i >= cantidad) return "Actividad inexistente.";
        actividades[i].completada = !actividades[i].completada;
        guardarActividades();
        return "Estado actualizado.";
    }

    static void ordenarSeleccion() {
        for (int i = 0; i < cantidad - 1; i++) {
            int menor = i;
            for (int j = i + 1; j < cantidad; j++)
                if (clave(actividades[j].nombre).compareTo(clave(actividades[menor].nombre)) < 0) menor = j;
            Actividad t = actividades[i]; actividades[i] = actividades[menor]; actividades[menor] = t;
        }
    }

    static void ordenarBurbuja() {
        for (int limite = cantidad - 1; limite > 0; limite--)
            for (int i = 0; i < limite; i++) if (actividades[i].fecha.compareTo(actividades[i + 1].fecha) > 0) {
                Actividad t = actividades[i]; actividades[i] = actividades[i + 1]; actividades[i + 1] = t;
            }
    }

    static String buscar(String nombre) {
        nombre = limpiar(nombre);
        if (nombre.isBlank()) return "Escribe un nombre exacto.";
        ordenarSeleccion();
        int izquierda = 0, derecha = cantidad - 1;
        while (izquierda <= derecha) {
            int centro = (izquierda + derecha) / 2;
            int c = clave(actividades[centro].nombre).compareTo(clave(nombre));
            if (c == 0) return "Encontrada: " + actividades[centro].nombre + " (posición " + centro + ").";
            if (c < 0) izquierda = centro + 1; else derecha = centro - 1;
        }
        return "No se encontró una coincidencia exacta.";
    }

    static int contarPendientes(int i) { return i == cantidad ? 0 : (actividades[i].completada ? 0 : 1) + contarPendientes(i + 1); }
    static double sumarHoras(int i) { return i == cantidad ? 0 : (actividades[i].completada ? 0 : actividades[i].horas) + sumarHoras(i + 1); }

    static String guardarBloque(Map<String, String> p) {
        int d = entero(p.get("dia"), -1), b = entero(p.get("bloque"), -1);
        String materia = limpiar(p.get("materia"));
        if (d < 0 || d >= 5 || b < 0 || b >= 6 || materia.length() < 2) return "Datos de horario inválidos.";
        horarios[d][b] = new Horario(materia, d, b);
        guardarHorarios();
        return "Bloque guardado.";
    }

    static String eliminarBloque(Map<String, String> p) {
        int d = entero(p.get("dia"), -1), b = entero(p.get("bloque"), -1);
        if (d < 0 || d >= 5 || b < 0 || b >= 6) return "Bloque inexistente.";
        horarios[d][b] = null;
        guardarHorarios();
        return "Bloque eliminado.";
    }

    static int contarBloques() {
        int total = 0;
        for (Horario[] dia : horarios) for (Horario h : dia) if (h != null) total++;
        return total;
    }

    // Listas, nodos y árbol binario.
    static void reconstruir() {
        cabezaLista = null; cabezaCircular = null; raizArbol = null;
        Nodo ultimo = null; NodoCircular ultimoC = null;
        for (int i = 0; i < cantidad; i++) {
            Actividad a = actividades[i];
            raizArbol = insertar(raizArbol, a);
            if (!a.completada) {
                Nodo n = new Nodo(a); if (cabezaLista == null) cabezaLista = n; else ultimo.siguiente = n; ultimo = n;
                NodoCircular c = new NodoCircular(a.nombre);
                if (cabezaCircular == null) cabezaCircular = c; else ultimoC.siguiente = c; ultimoC = c;
            }
        }
        if (ultimoC != null) ultimoC.siguiente = cabezaCircular;
    }

    static NodoArbol insertar(NodoArbol n, Actividad a) {
        if (n == null) return new NodoArbol(a);
        if (clave(a.nombre).compareTo(clave(n.a.nombre)) < 0) n.izquierda = insertar(n.izquierda, a);
        else n.derecha = insertar(n.derecha, a);
        return n;
    }

    static void inorden(NodoArbol n, StringBuilder s) {
        if (n == null) return;
        inorden(n.izquierda, s); s.append("<li>").append(esc(n.a.nombre)).append("</li>"); inorden(n.derecha, s);
    }

    static int altura(NodoArbol n) { return n == null ? 0 : 1 + Math.max(altura(n.izquierda), altura(n.derecha)); }
    static int contarNodos(Nodo n) { return n == null ? 0 : 1 + contarNodos(n.siguiente); }
    static String siguienteCircular() {
        reconstruir();
        if (cabezaCircular == null) return "No hay actividades pendientes.";
        NodoCircular actual = cabezaCircular;
        int total = contarNodos(cabezaLista);
        for (int i = 0; i < pasoCircular % total; i++) actual = actual.siguiente;
        pasoCircular++;
        return "Recuerda: " + actual.mensaje;
    }

    // Persistencia TXT.
    static void guardarActividades() {
        DATOS.mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(ARCHIVO_A, StandardCharsets.UTF_8, false))) {
            for (int i = 0; i < cantidad; i++) {
                Actividad a = actividades[i];
                w.write(a.nombre + ";" + a.fecha + ";" + a.prioridad + ";" + a.horas + ";" + a.completada); w.newLine();
            }
        } catch (IOException ignored) { }
    }

    static void guardarHorarios() {
        DATOS.mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(ARCHIVO_H, StandardCharsets.UTF_8, false))) {
            for (int d = 0; d < 5; d++) for (int b = 0; b < 6; b++) if (horarios[d][b] != null) {
                w.write(d + ";" + b + ";" + horarios[d][b].materia); w.newLine();
            }
        } catch (IOException ignored) { }
    }

    static void cargar() {
        DATOS.mkdirs();
        if (ARCHIVO_A.exists()) try (BufferedReader r = new BufferedReader(new FileReader(ARCHIVO_A, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null && cantidad < 100) {
                String[] d = linea.split(";");
                if (d.length == 5) actividades[cantidad++] = new Actividad(d[0], d[1], d[2], Double.parseDouble(d[3]), Boolean.parseBoolean(d[4]));
            }
        } catch (Exception ignored) { }
        if (ARCHIVO_H.exists()) try (BufferedReader r = new BufferedReader(new FileReader(ARCHIVO_H, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = r.readLine()) != null) {
                String[] d = linea.split(";");
                if (d.length == 3) { int dia = Integer.parseInt(d[0]), b = Integer.parseInt(d[1]); horarios[dia][b] = new Horario(d[2], dia, b); }
            }
        } catch (Exception ignored) { }
        if (cantidad == 0) {
            actividades[cantidad++] = new Actividad("Proyecto de Programación", LocalDate.now().plusDays(4).toString(), "Alta", 3, false);
            actividades[cantidad++] = new Actividad("Taller de Cálculo", LocalDate.now().plusDays(7).toString(), "Media", 2, false);
            actividades[cantidad++] = new Actividad("Exposición de Sistemas", LocalDate.now().plusDays(10).toString(), "Alta", 2.5, false);
            horarios[0][0] = new Horario("Programación II", 0, 0); horarios[2][2] = new Horario("Álgebra", 2, 2);
            guardarActividades(); guardarHorarios();
        }
    }

    // Utilidades HTTP y HTML.
    static Map<String, String> parametros(HttpExchange e) throws IOException {
        String datos = e.getRequestURI().getRawQuery();
        if ("POST".equals(e.getRequestMethod())) datos = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> mapa = new HashMap<>();
        if (datos != null) for (String parte : datos.split("&")) {
            String[] par = parte.split("=", 2);
            mapa.put(decodificar(par[0]), par.length == 2 ? decodificar(par[1]) : "");
        }
        return mapa;
    }

    static void responder(HttpExchange e, int estado, String tipo, String texto) throws IOException {
        byte[] datos = texto.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", tipo + "; charset=UTF-8");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.sendResponseHeaders(estado, datos.length);
        try (OutputStream salida = e.getResponseBody()) { salida.write(datos); }
    }

    static void redirigir(HttpExchange e, String ruta) throws IOException {
        e.getResponseHeaders().set("Location", ruta); e.sendResponseHeaders(303, -1); e.close();
    }

    static String leerCss() {
        try { return new String(java.nio.file.Files.readAllBytes(new File("styles.css").toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return "body{font-family:sans-serif}"; }
    }

    static String metrica(String titulo, Object valor) { return "<article class='metrica'><span>" + titulo + "</span><strong>" + valor + "</strong></article>"; }
    static String formBoton(String accion, int i, String texto, String clase) { return "<form method='post' action='" + accion + "'><input type='hidden' name='indice' value='" + i + "'><button class='" + clase + "'>" + texto + "</button></form>"; }
    static String opciones(String seleccionada) { StringBuilder s = new StringBuilder(); for (String p : new String[]{"Alta", "Media", "Baja"}) s.append("<option").append(p.equals(seleccionada) ? " selected" : "").append(">").append(p).append("</option>"); return s.toString(); }
    static String opcionesIndice(String[] datos) { StringBuilder s = new StringBuilder(); for (int i = 0; i < datos.length; i++) s.append("<option value='").append(i).append("'>").append(datos[i]).append("</option>"); return s.toString(); }
    static String encabezados(String[] datos) { StringBuilder s = new StringBuilder(); for (String dato : datos) s.append("<th>").append(dato).append("</th>"); return s.toString(); }
    static String limpiar(String s) { return s == null ? "" : s.trim().replace(";", " "); }
    static String clave(String s) { return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(); }
    static int entero(String s, int defecto) { try { return Integer.parseInt(s); } catch (Exception e) { return defecto; } }
    static String codificar(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String decodificar(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }

    static class Actividad {
        String nombre, fecha, prioridad; double horas; boolean completada;
        Actividad(String n, String f, String p, double h, boolean c) { nombre = n; fecha = f; prioridad = p; horas = h; completada = c; }
    }
    static class Horario { String materia; int dia, bloque; Horario(String m, int d, int b) { materia = m; dia = d; bloque = b; } }
    static class Nodo { Actividad a; Nodo siguiente; Nodo(Actividad a) { this.a = a; } }
    static class NodoCircular { String mensaje; NodoCircular siguiente; NodoCircular(String m) { mensaje = m; } }
    static class NodoArbol { Actividad a; NodoArbol izquierda, derecha; NodoArbol(Actividad a) { this.a = a; } }
}
