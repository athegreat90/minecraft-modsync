import java.net.URI;
import java.net.http.*;
import java.time.Duration;
public class Health {
 public static void main(String[] args) throws Exception {
  String url = System.getenv().getOrDefault("MODSYNC_HEALTH_URL", "http://localhost:8080/actuator/health");
  var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
  if (response.statusCode() != 200) System.exit(1);
 }
}
