package org.taniwha.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeMetadataTest {

    @Test
    void testNodeMetadataConstructor() {
        NodeMetadata metadata = new NodeMetadata();
        assertNotNull(metadata);
        assertNull(metadata.getContext());
        assertNull(metadata.getDataset());
    }

    @Test
    void testNodeMetadataSetters() {
        NodeMetadata metadata = new NodeMetadata();

        metadata.setContext("https://www.w3.org/ns/dcat.jsonld");

        List<NodeMetadata.Dataset> datasets = new ArrayList<>();
        metadata.setDataset(datasets);

        assertEquals("https://www.w3.org/ns/dcat.jsonld", metadata.getContext());
        assertNotNull(metadata.getDataset());
        assertEquals(0, metadata.getDataset().size());
    }

    @Test
    void testDatasetGettersAndSetters() {
        NodeMetadata.Dataset dataset = new NodeMetadata.Dataset();

        dataset.setTitle("Test Dataset");
        dataset.setDescription("A test dataset");
        dataset.setIdentifier("dataset-001");
        dataset.setIssued("2024-01-01");
        dataset.setModified("2024-01-15");
        dataset.setAccrualPeriodicity("monthly");
        dataset.setPublisher("Test Publisher");
        dataset.setContactPoint("contact@test.com");
        dataset.setSpatial("Global");
        dataset.setTemporal("2024");

        assertEquals("Test Dataset", dataset.getTitle());
        assertEquals("A test dataset", dataset.getDescription());
        assertEquals("dataset-001", dataset.getIdentifier());
        assertEquals("2024-01-01", dataset.getIssued());
        assertEquals("2024-01-15", dataset.getModified());
        assertEquals("monthly", dataset.getAccrualPeriodicity());
        assertEquals("Test Publisher", dataset.getPublisher());
        assertEquals("contact@test.com", dataset.getContactPoint());
        assertEquals("Global", dataset.getSpatial());
        assertEquals("2024", dataset.getTemporal());
    }

    @Test
    void testDatasetWithCollections() {
        NodeMetadata.Dataset dataset = new NodeMetadata.Dataset();

        List<String> keywords = Arrays.asList("health", "data", "research");
        List<String> themes = Arrays.asList("healthcare", "medicine");
        List<String> languages = Arrays.asList("en", "es");

        dataset.setKeyword(keywords);
        dataset.setTheme(themes);
        dataset.setLanguage(languages);

        assertEquals(3, dataset.getKeyword().size());
        assertEquals(2, dataset.getTheme().size());
        assertEquals(2, dataset.getLanguage().size());
        assertTrue(dataset.getKeyword().contains("health"));
        assertTrue(dataset.getTheme().contains("healthcare"));
        assertTrue(dataset.getLanguage().contains("en"));
    }

    @Test
    void testDatasetWithDistributions() {
        NodeMetadata.Dataset dataset = new NodeMetadata.Dataset();

        NodeMetadata.Distribution dist1 = new NodeMetadata.Distribution();
        dist1.setTitle("CSV Distribution");
        dist1.setFormat("CSV");

        NodeMetadata.Distribution dist2 = new NodeMetadata.Distribution();
        dist2.setTitle("JSON Distribution");
        dist2.setFormat("JSON");

        List<NodeMetadata.Distribution> distributions = Arrays.asList(dist1, dist2);
        dataset.setDistribution(distributions);

        assertNotNull(dataset.getDistribution());
        assertEquals(2, dataset.getDistribution().size());
        assertEquals("CSV", dataset.getDistribution().get(0).getFormat());
        assertEquals("JSON", dataset.getDistribution().get(1).getFormat());
    }

    @Test
    void testDistributionGettersAndSetters() {
        NodeMetadata.Distribution distribution = new NodeMetadata.Distribution();

        distribution.setTitle("CSV File");
        distribution.setDescription("CSV format data");
        distribution.setFormat("text/csv");
        distribution.setLicense("MIT");
        distribution.setDownloadURL("https://example.com/data.csv");

        assertEquals("CSV File", distribution.getTitle());
        assertEquals("CSV format data", distribution.getDescription());
        assertEquals("text/csv", distribution.getFormat());
        assertEquals("MIT", distribution.getLicense());
        assertEquals("https://example.com/data.csv", distribution.getDownloadURL());
    }

    @Test
    void testCompleteNodeMetadata() {
        NodeMetadata metadata = new NodeMetadata();
        metadata.setContext("https://www.w3.org/ns/dcat.jsonld");

        NodeMetadata.Dataset dataset = new NodeMetadata.Dataset();
        dataset.setTitle("Health Records");
        dataset.setDescription("Patient health records dataset");
        dataset.setIdentifier("hr-001");

        NodeMetadata.Distribution distribution = new NodeMetadata.Distribution();
        distribution.setTitle("CSV Export");
        distribution.setFormat("CSV");
        distribution.setDownloadURL("https://example.com/health.csv");

        dataset.setDistribution(Collections.singletonList(distribution));
        metadata.setDataset(Collections.singletonList(dataset));

        assertNotNull(metadata.getDataset());
        assertEquals(1, metadata.getDataset().size());
        assertEquals("Health Records", metadata.getDataset().get(0).getTitle());
        assertEquals(1, metadata.getDataset().get(0).getDistribution().size());
        assertEquals("CSV Export", metadata.getDataset().get(0).getDistribution().get(0).getTitle());
    }

    @Test
    void testDatasetNullCollections() {
        NodeMetadata.Dataset dataset = new NodeMetadata.Dataset();

        assertNull(dataset.getKeyword());
        assertNull(dataset.getTheme());
        assertNull(dataset.getLanguage());
        assertNull(dataset.getDistribution());
    }

    @Test
    void testDistributionNullValues() {
        NodeMetadata.Distribution distribution = new NodeMetadata.Distribution();

        assertNull(distribution.getTitle());
        assertNull(distribution.getDescription());
        assertNull(distribution.getFormat());
        assertNull(distribution.getLicense());
        assertNull(distribution.getDownloadURL());
    }
}
