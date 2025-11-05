package sa.com.cloudsolutions.antikythera.examples.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.List;
import java.util.Objects;

/**
 * Represents information about a method for analysis purposes.
 */
public class MethodInfo {
    private final String className;
    private final String methodName;
    private final MethodDeclaration methodDeclaration;
    private final String signature;
    private final String body;
    private final List<String> methodCalls;
    
    public MethodInfo(String className, String methodName, MethodDeclaration methodDeclaration,
                     String signature, String body, List<String> methodCalls) {
        this.className = className;
        this.methodName = methodName;
        this.methodDeclaration = methodDeclaration;
        this.signature = signature;
        this.body = body;
        this.methodCalls = methodCalls;
    }
    
    public String getClassName() {
        return className;
    }
    
    public String getMethodName() {
        return methodName;
    }
    
    public MethodDeclaration getMethodDeclaration() {
        return methodDeclaration;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public String getBody() {
        return body;
    }
    
    public List<String> getMethodCalls() {
        return methodCalls;
    }
    
    public String getFullMethodName() {
        return className + "." + methodName;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodInfo that = (MethodInfo) o;
        return Objects.equals(className, that.className) &&
               Objects.equals(methodName, that.methodName) &&
               Objects.equals(signature, that.signature);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, signature);
    }
    
    @Override
    public String toString() {
        return String.format("MethodInfo{%s.%s}", className, methodName);
    }
}