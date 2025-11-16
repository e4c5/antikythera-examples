package sa.com.cloudsolutions.antikythera.examples;

import java.util.Objects;

/**
 * Represents a JOIN ON clause condition from a repository query.
 * Contains information about the left and right tables, columns, and operator
 * used in the JOIN condition.
 */
public final class JoinCondition {
    private String leftTable;
    private final String leftColumn;
    private String rightTable;
    private final String rightColumn;
    private final String operator;
    private final int position;

    public JoinCondition(String leftTable,
                         String leftColumn,
                         String rightTable,
                         String rightColumn,
                         String operator,
                         int position) {
        this.leftTable = leftTable;
        this.leftColumn = leftColumn;
        this.rightTable = rightTable;
        this.rightColumn = rightColumn;
        this.operator = operator;
        this.position = position;
    }

    public String getLeftTable() {
        return leftTable;
    }

    public String getLeftColumn() {
        return leftColumn;
    }

    public String getRightTable() {
        return rightTable;
    }

    public String getRightColumn() {
        return rightColumn;
    }

    public String getOperator() {
        return operator;
    }

    public int getPosition() {
        return position;
    }

    // Backward-compatible record-style accessors
    public String leftTable() { return getLeftTable(); }
    public String leftColumn() { return getLeftColumn(); }
    public String rightTable() { return getRightTable(); }
    public String rightColumn() { return getRightColumn(); }
    public String operator() { return getOperator(); }
    public int position() { return getPosition(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JoinCondition that = (JoinCondition) o;
        return position == that.position &&
                Objects.equals(leftTable, that.leftTable) &&
                Objects.equals(leftColumn, that.leftColumn) &&
                Objects.equals(rightTable, that.rightTable) &&
                Objects.equals(rightColumn, that.rightColumn) &&
                Objects.equals(operator, that.operator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftTable, leftColumn, rightTable, rightColumn, operator, position);
    }

    @Override
    public String toString() {
        return "JoinCondition{" +
                "leftTable='" + leftTable + '\'' +
                ", leftColumn='" + leftColumn + '\'' +
                ", rightTable='" + rightTable + '\'' +
                ", rightColumn='" + rightColumn + '\'' +
                ", operator='" + operator + '\'' +
                ", position=" + position +
                '}';
    }

    public void setLeftTable(String leftTable) {
        this.leftTable = leftTable;
    }

    public void setRightTable(String rightTable) {
        this.rightTable = rightTable;
    }
}
