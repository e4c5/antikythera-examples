package sa.com.cloudsolutions.antikythera.examples;

import sa.com.cloudsolutions.antikythera.generator.RepositoryQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups RepositoryQuery objects by repository for batch processing with AI service.
 * Contains queries from a single repository along with their cardinality information.
 */
public class QueryBatch {
    private String repositoryName;
    private List<RepositoryQuery> queries;
    private Map<String, CardinalityLevel> columnCardinalities;

    public QueryBatch() {
        this.queries = new ArrayList<>();
        this.columnCardinalities = new HashMap<>();
    }

    public QueryBatch(String repositoryName) {
        this.repositoryName = repositoryName;
        this.queries = new ArrayList<>();
        this.columnCardinalities = new HashMap<>();
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public List<RepositoryQuery> getQueries() {
        return queries;
    }

    public void setQueries(List<RepositoryQuery> queries) {
        this.queries = queries != null ? queries : new ArrayList<>();
    }

    public Map<String, CardinalityLevel> getColumnCardinalities() {
        return columnCardinalities;
    }

    public void setColumnCardinalities(Map<String, CardinalityLevel> columnCardinalities) {
        this.columnCardinalities = columnCardinalities != null ? columnCardinalities : new HashMap<>();
    }

    /**
     * Adds a query to this batch.
     */
    public void addQuery(RepositoryQuery query) {
        if (query != null) {
            this.queries.add(query);
        }
    }

    /**
     * Adds cardinality information for a column.
     */
    public void addColumnCardinality(String columnName, CardinalityLevel cardinality) {
        if (columnName != null && cardinality != null) {
            this.columnCardinalities.put(columnName, cardinality);
        }
    }

    /**
     * Gets the cardinality level for a specific column.
     */
    public CardinalityLevel getColumnCardinality(String columnName) {
        return columnCardinalities.get(columnName);
    }

    /**
     * Returns the number of queries in this batch.
     */
    public int size() {
        return queries.size();
    }

    /**
     * Checks if this batch is empty.
     */
    public boolean isEmpty() {
        return queries.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("QueryBatch{repositoryName='%s', queryCount=%d, cardinalityCount=%d}",
                repositoryName, queries.size(), columnCardinalities.size());
    }
}