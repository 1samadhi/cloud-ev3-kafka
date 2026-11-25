package com.kafka.recomendaciones.Config;

import com.kafka.recomendaciones.Model.Producto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Catalogo semilla de §7.1 (misma fuente de verdad que ms-productos y la tienda).
 *
 * Reemplaza al viejo ProductoConfig, pero ya NO es el catalogo: es el respaldo
 * mientras catalogo-productos esta vacio (ms-productos aun no publico, o el
 * servicio arranco sin Kafka). En cuanto llega el primer snapshot del topico,
 * manda el topico.
 */
@Component
public class CatalogoSemilla {

    /** Etiqueta legible de cada slug de categoria, para los motivos ("Te interesa Audio"). */
    private static final Map<String, String> ETIQUETAS = Map.of(
            "celulares", "Celulares",
            "laptops", "Laptops",
            "audio", "Audio",
            "gaming", "Gaming",
            "tv-video", "TV y video",
            "smart-home", "Smart home",
            "accesorios", "Accesorios",
            "camaras", "Cámaras");

    private static final List<Producto> PRODUCTOS = List.of(
            p("item_101", "Galaxy S24", "Samsung", "celulares", "gama-alta", 649990, 14, 4.7, "6.2\" AMOLED, 256 GB."),
            p("item_102", "iPhone 15", "Apple", "celulares", "gama-alta", 799990, 6, 4.8, "Chip A16, cámara de 48 MP."),
            p("item_103", "Moto G84", "Motorola", "celulares", "gama-media", 229990, 40, 4.4, "Pantalla pOLED, 5G."),
            p("item_104", "ZenBook 14", "ASUS", "laptops", "ultraliviana", 749990, 22, 4.6, "Ryzen 7, 16 GB, SSD 512 GB."),
            p("item_105", "MacBook Air M2", "Apple", "laptops", "ultraliviana", 1099990, 9, 4.9, "13.6\", 8 GB, 256 GB."),
            p("item_106", "IdeaPad Slim 3", "Lenovo", "laptops", "uso-diario", 429990, 33, 4.3, "Core i5, 8 GB, SSD 512 GB."),
            p("item_107", "WH-1000XM5", "Sony", "audio", "audifonos", 299990, 18, 4.8, "Cancelación de ruido activa."),
            p("item_108", "AirPods Pro 2", "Apple", "audio", "audifonos", 249990, 12, 4.7, "Audio espacial, estuche USB-C."),
            p("item_109", "Flip 6", "JBL", "audio", "parlantes", 119990, 52, 4.6, "Parlante portátil IP67."),
            p("item_110", "PlayStation 5", "Sony", "gaming", "consolas", 549990, 5, 4.9, "Consola con lector de discos."),
            p("item_111", "Switch OLED", "Nintendo", "gaming", "consolas", 329990, 27, 4.8, "Pantalla OLED de 7\"."),
            p("item_112", "DualSense", "Sony", "gaming", "controles", 74990, 70, 4.6, "Control inalámbrico háptico."),
            p("item_113", "Bravia 55\" 4K", "Sony", "tv-video", "televisores", 699990, 10, 4.6, "Google TV, HDR10, 120 Hz."),
            p("item_114", "Fire TV Stick 4K", "Amazon", "tv-video", "streaming", 59990, 45, 4.4, "Streaming 4K con Alexa."),
            p("item_115", "Echo Dot 5", "Amazon", "smart-home", "asistentes", 54990, 38, 4.5, "Parlante inteligente con Alexa."),
            p("item_116", "Hue White & Color", "Philips", "smart-home", "iluminacion", 39990, 60, 4.4, "Ampolleta inteligente E27."),
            p("item_117", "Cargador GaN 65 W", "Anker", "accesorios", "carga", 34990, 80, 4.7, "USB-C, carga rápida para laptop."),
            p("item_118", "Funda MagSafe iPhone 15", "Apple", "accesorios", "fundas", 49990, 35, 4.3, "Silicona con imanes MagSafe."),
            p("item_119", "Alpha a6400", "Sony", "camaras", "mirrorless", 899990, 7, 4.8, "24 MP, enfoque en tiempo real."),
            p("item_120", "HERO12 Black", "GoPro", "camaras", "accion", 429990, 15, 4.7, "5.3K, sumergible 10 m."));

    /** imagenUrl = null a proposito (§7.1): la tienda muestra un placeholder. */
    private static Producto p(String id, String nombre, String marca, String categoria, String subcategoria,
                              long precio, int stock, double calificacion, String descripcion) {
        return Producto.builder()
                .id(id).nombre(nombre).marca(marca).categoria(categoria).subcategoria(subcategoria)
                .precio(precio).stock(stock).calificacion(calificacion).descripcion(descripcion)
                .imagenUrl(null).actualizadoEn(0L)
                .build();
    }

    public List<Producto> productos() {
        return PRODUCTOS;
    }

    public static String etiquetaCategoria(String slug) {
        if (slug == null) {
            return "esta categoría";
        }
        return ETIQUETAS.getOrDefault(slug, slug);
    }
}
