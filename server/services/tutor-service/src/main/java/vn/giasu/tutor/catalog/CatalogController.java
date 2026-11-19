package vn.giasu.tutor.catalog;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {
    record Item(String code, String name) {}
    record Area(String code, String province, String name) {}
    record Catalog(List<Item> subjects, List<Item> levels, List<Area> areas) {}

    private final JdbcClient jdbc;

    CatalogController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/tutors/catalog")
    Catalog catalog() {
        return new Catalog(
                jdbc.sql("SELECT code, name FROM subjects ORDER BY name").query(Item.class).list(),
                jdbc.sql("SELECT code, name FROM levels ORDER BY sort_order").query(Item.class).list(),
                jdbc.sql("SELECT code, province, name FROM areas ORDER BY province, name").query(Area.class).list());
    }
}
