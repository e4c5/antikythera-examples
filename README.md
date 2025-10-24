# antikythera-examples

Using existing local sources of sa.com.cloudsolutions:antikythera in this project

If you already have the antikythera sources on your machine, you can debug and step into them from this examples project without downloading any sources. Pick the approach that best fits your workflow.

Option A (recommended): Open antikythera as a module in the same IntelliJ window
- File > New > Module from Existing Sources…
- Select the antikythera project's pom.xml on your disk.
- Confirm to import it as a Maven project. IntelliJ will add it as another module alongside this examples module.
- In the Maven tool window, click "Reload All Maven Projects" to ensure dependencies are resolved.
- Result: IntelliJ uses the module output instead of the binary JAR. You can build both projects together and step directly into antikythera code during debugging.

Option B: Attach your local source folder to the library JAR
- Open File > Project Structure… > Libraries.
- Locate the antikythera library (sa.com.cloudsolutions:antikythera:0.1.1) that this project depends on.
- Click "Attach Sources…" and select the local source root directory of the antikythera project.
- Apply and close. IntelliJ will show the library with attached sources, enabling navigation and Step Into during debugging.

Option C (alternative workflow): Work with both projects in one Maven workspace
- Simply open both projects in the same IntelliJ window using the Maven view (no parent/aggregator change needed).
- Or, if you have a parent project that lists both modules, open the parent so IntelliJ imports both modules automatically.

Verification
- Set breakpoints in the examples project where it calls into antikythera APIs.
- Start a Debug session.
- Use Step Into (F7) when execution enters antikythera classes. The editor should open the source files from your local checkout.

Troubleshooting
- Maven reimport: Use the Maven tool window > Reload All Maven Projects after changes.
- SDK/language level: Ensure Project SDK is JDK 21 and language level matches (we set maven-compiler-plugin <release>21</release> for consistency).
- Caches: If IntelliJ still doesn’t link sources, try File > Invalidate Caches / Restart.

Notes
- No repository changes are required for any of the approaches above. The pom already declares dependencies explicitly so IntelliJ resolves them reliably. 
