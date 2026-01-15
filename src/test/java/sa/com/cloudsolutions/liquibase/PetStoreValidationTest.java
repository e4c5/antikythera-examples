package sa.com.cloudsolutions.liquibase;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.liquibase.Indexes.IndexInfo;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PetStoreValidationTest {

    @Test
    void validatePetStoreSchema() throws Exception {
        File masterFile = new File("src/test/resources/petstore-lb/db-changelog-master.xml");

        // Validate Columns
        Map<String, List<String>> columns = Columns.load(masterFile);

        // Category
        assertTrue(columns.containsKey("category"), "Category table missing");
        List<String> categoryCols = columns.get("category");
        assertTrue(categoryCols.contains("id"));
        assertTrue(categoryCols.contains("name"));
        assertTrue(categoryCols.contains("description"));

        // Product
        assertTrue(columns.containsKey("product"), "Product table missing");
        List<String> productCols = columns.get("product");
        assertTrue(productCols.contains("id"));
        assertTrue(productCols.contains("name"));
        assertTrue(productCols.contains("category_id"));
        assertTrue(productCols.contains("image_url"));

        // Orders
        assertTrue(columns.containsKey("orders"), "Orders table missing");
        List<String> orderCols = columns.get("orders");
        assertTrue(orderCols.contains("id"));
        assertTrue(orderCols.contains("customer_id"));
        assertTrue(orderCols.contains("status"));
        assertTrue(orderCols.contains("ship_date"));

        // Validate Indexes
        Map<String, Set<IndexInfo>> indexes = Indexes.load(masterFile);

        // Category Indexes
        Set<IndexInfo> catIndexes = indexes.get("category");
        assertNotNull(catIndexes, "Category indexes missing");
        assertTrue(catIndexes.stream().anyMatch(i -> Indexes.PRIMARY_KEY.equals(i.type())), "Category PK missing");
        assertTrue(catIndexes.stream().anyMatch(i -> "idx_category_name".equals(i.name())), "idx_category_name missing");

        // Product Indexes
        Set<IndexInfo> prodIndexes = indexes.get("product");
        assertNotNull(prodIndexes, "Product indexes missing");
        assertTrue(prodIndexes.stream().anyMatch(i -> Indexes.PRIMARY_KEY.equals(i.type())), "Product PK missing");
        assertTrue(prodIndexes.stream().anyMatch(i -> "idx_product_category".equals(i.name())), "idx_product_category missing");

        // Verify dropped index
        assertFalse(prodIndexes.stream().anyMatch(i -> "idx_product_name".equals(i.name())), "idx_product_name should have been dropped");

        // Order Indexes
        Set<IndexInfo> orderIndexes = indexes.get("orders");
        assertNotNull(orderIndexes, "Orders indexes missing");
        assertTrue(orderIndexes.stream().anyMatch(i -> Indexes.PRIMARY_KEY.equals(i.type())), "Orders PK missing");
        assertTrue(orderIndexes.stream().anyMatch(i -> "idx_order_customer".equals(i.name())), "idx_order_customer missing");
    }
}
