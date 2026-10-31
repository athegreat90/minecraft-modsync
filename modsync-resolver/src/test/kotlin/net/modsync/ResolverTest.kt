package net.modsync
import org.junit.jupiter.api.Test
import kotlin.test.*
class ResolverTest {
    private fun artifact(id: String="a", kind: Classification=Classification.BOTH_REQUIRED) = Artifact(id,"1","$id.jar","a".repeat(128),10,"https://cdn.modrinth.com/data/$id.jar",setOf("1.21.1"),setOf("neoforge"),kind)
    private fun manifest(vararg a: Artifact) = Manifest("00000000-0000-0000-0000-000000000001",1,"1.21.1","neoforge",artifacts=a.toList(),createdAt="2026-01-01T00:00:00Z",inventoryDigest="a".repeat(128))
    @Test fun `embedded provider satisfies required dependency`() {
        val a=artifact().copy(dependencies=listOf(Dependency("library","1",DependencyKind.EMBEDDED)))
        val b=artifact("b").copy(dependencies=listOf(Dependency("library","1")))
        assertFalse(Resolver().plan(manifest(a,b),emptyList(),emptyList(),emptyMap()).blocked)
    }
    @Test fun `optional server artifact requires opt in`() {
        val m=manifest(artifact("optional",Classification.CLIENT_OPTIONAL))
        assertTrue(Resolver().plan(m,emptyList(),emptyList(),emptyMap()).operations.isEmpty())
        assertEquals(OperationKind.INSTALL,Resolver().plan(m,emptyList(),emptyList(),emptyMap(),includeOptional=true).operations.single().kind)
    }
    @Test fun `server only excluded and unmanaged preserved`() {
        val plan=Resolver().plan(manifest(artifact(),artifact("server",Classification.SERVER_ONLY)),emptyList(),listOf(ModEntry("personal.jar","b".repeat(128),1)),emptyMap())
        assertFalse(plan.blocked); assertTrue(plan.operations.any { it.kind==OperationKind.INSTALL }); assertTrue(plan.operations.any { it.kind==OperationKind.SKIP }); assertFalse(plan.operations.any { it.fileName=="server.jar" })
    }
    @Test fun `exact version conflicts are not upgraded`() { assertTrue(Resolver().plan(manifest(artifact()),listOf(artifact().copy(versionId="2",classification=Classification.CLIENT_ONLY)),emptyList(),emptyMap()).blocked) }
    @Test fun `required dependency must resolve before download`() { val a=artifact().copy(dependencies=listOf(Dependency("missing"))); assertTrue(Resolver().plan(manifest(a),emptyList(),emptyList(),emptyMap()).blocked) }
    @Test fun `cycle of exact resolved artifacts terminates`() { val a=artifact().copy(dependencies=listOf(Dependency("b"))); val b=artifact("b").copy(dependencies=listOf(Dependency("a"))); assertFalse(Resolver().plan(manifest(a,b),emptyList(),emptyList(),emptyMap()).blocked) }
    @Test fun `private artifacts require manual resolution`() { assertTrue(Resolver().plan(manifest(artifact().copy(url=null)),emptyList(),emptyList(),emptyMap()).blocked) }
    @Test fun `repeat synchronization keeps verified artifact`() { val a=artifact(); val plan=Resolver().plan(manifest(a),emptyList(),listOf(ModEntry(a.fileName,a.sha512,a.size)),mapOf(a.fileName to a.sha512)); assertEquals(OperationKind.KEEP,plan.operations.single().kind) }
    @Test fun `unmanaged collisions never overwrite`() { val a=artifact(); assertTrue(Resolver().plan(manifest(a),emptyList(),listOf(ModEntry(a.fileName,"b".repeat(128),1)),emptyMap()).blocked) }
}
