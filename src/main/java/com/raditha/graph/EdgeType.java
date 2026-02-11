package com.raditha.graph;

/**
 * Enumeration of edge types in the Knowledge Graph.
 * Each type represents a different relationship between code elements.
 */
public enum EdgeType {
    /** Structural: Class contains a member (method, field, static block). */
    CONTAINS,

    /** Structural: Outer class/method encloses an inner class, anonymous class, or lambda. */
    ENCLOSES,

    /** Structural: A class implements an interface. */
    IMPLEMENTS,

    /** Structural: A class extends another class. */
    EXTENDS,

    /** Behavioral: A method/block invokes another method. */
    CALLS,

    /** Behavioral: A method/block reads or writes a field. */
    ACCESSES,

    /** Dependency: A method/class uses a type (in parameters, return types, casts, etc.). */
    USES,

    /** Behavioral: A method/block references a method (e.g. method references). */
    REFERENCES
}
