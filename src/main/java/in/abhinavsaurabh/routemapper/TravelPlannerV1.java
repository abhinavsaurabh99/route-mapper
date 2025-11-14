package in.abhinavsaurabh.routemapper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class TravelPlannerV1
{
    public static void main(String[] args) throws Exception
    {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter Source City: ");
        String source = scanner.nextLine().trim();
        System.out.print("Enter Destination City: ");
        String destination = scanner.nextLine().trim();

        // Get coordinates for both cities
        double[] srcCoords = getLatLng(source);
        double[] destCoords = getLatLng(destination);

        if (srcCoords == null || destCoords == null)
        {
            System.out.println("Unable to find one of the city locations.");
            return;
        }

        // Fetch route from OSRM
        RouteResult routeResult = getRoute(srcCoords, destCoords);

        if (routeResult == null)
        {
            System.out.println("No route found.");
            return;
        }

        System.out.printf("Route Distance: %.2f km%n", routeResult.distanceKm);
        System.out.printf("Estimated Duration: %.2f hours%n", routeResult.durationHrs);

        // Generate HTML map
        generateMapHTML(source, destination, srcCoords, destCoords, routeResult.waypoints);

        // Open map
        Desktop.getDesktop().browse(Paths.get("route_map.html").toUri());
    }

    // Helper Class
    static class RouteResult
    {
        List<double[]> waypoints;
        double distanceKm;
        double durationHrs;

        RouteResult(List<double[]> waypoints, double distanceKm, double durationHrs)
        {
            this.waypoints = waypoints;
            this.distanceKm = distanceKm;
            this.durationHrs = durationHrs;
        }
    }

    // Get Latitude & Longitude using OpenStreetMap Nominatim
    public static double[] getLatLng(String placeName)
    {
        try
        {
            String encodedPlace = URLEncoder.encode(placeName, "UTF-8");
            String urlStr = "https://nominatim.openstreetmap.org/search?q=" + encodedPlace + "&format=json&limit=1";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java App)");
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
            reader.close();
            JSONArray arr = new JSONArray(response.toString());
            if (arr.length() == 0) return null;
            JSONObject obj = arr.getJSONObject(0);
            return new double[]{obj.getDouble("lat"), obj.getDouble("lon")};
        }
        catch (Exception e)
        {
            System.err.println("Error fetching coordinates for " + placeName + ": " + e.getMessage());
            return null;
        }
    }

    // Get Route from OSRM
    public static RouteResult getRoute(double[] src, double[] dest)
    {
        try
        {
            String urlStr = String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                    src[1], src[0], dest[1], dest[0]);

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java App)");
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray routes = json.getJSONArray("routes");
            if (routes.isEmpty())
            {
                return null;
            }

            JSONObject route = routes.getJSONObject(0);
            double distanceKm = route.getDouble("distance") / 1000.0;
            double durationHrs = route.getDouble("duration") / 3600.0;

            JSONArray coords = route.getJSONObject("geometry").getJSONArray("coordinates");
            List<double[]> waypoints = new ArrayList<>();
            for (int i = 0; i < coords.length(); i++)
            {
                JSONArray pair = coords.getJSONArray(i);
                waypoints.add(new double[]{pair.getDouble(1), pair.getDouble(0)});
            }

            return new RouteResult(waypoints, distanceKm, durationHrs);

        }
        catch (Exception e)
        {
            System.err.println("Error fetching route: " + e.getMessage());
            return null;
        }
    }

    // Generate Interactive Map
    public static void generateMapHTML(String source, String destination, double[] srcCoords,
                                       double[] destCoords, List<double[]> waypoints) throws Exception
    {

        StringBuilder pathLine = new StringBuilder("[");
        for (double[] wp : waypoints)
        {
            pathLine.append(String.format("[%f,%f],", wp[0], wp[1]));
        }
        if (pathLine.charAt(pathLine.length() - 1) == ',')
        {
            pathLine.setLength(pathLine.length() - 1);
        }
        pathLine.append("]");

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8"/>
                  <title>Interactive Travel Route</title>
                  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
                  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                  <style>#map { height: 100vh; }</style>
                </head>
                <body>
                  <div id="map"></div>
                  <script>
                    var map = L.map('map').setView([%f,%f], 6);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                      attribution: '© OpenStreetMap contributors'
                    }).addTo(map);

                    var route = L.polyline(%s, {color: 'blue', weight: 5}).addTo(map);
                    L.marker([%f,%f]).addTo(map).bindPopup("Start: %s").openPopup();
                    L.marker([%f,%f]).addTo(map).bindPopup("Destination: %s");

                    // Highlight key midpoints
                    var midpoints = [route.getLatLngs()[Math.floor(route.getLatLngs().length/3)],
                                     route.getLatLngs()[Math.floor(2*route.getLatLngs().length/3)]];
                    midpoints.forEach(p => L.circleMarker(p, {radius:6, color:'red'}).addTo(map).bindPopup('Important Stop'));
                    map.fitBounds(route.getBounds());
                  </script>
                </body>
                </html>
                """.formatted(srcCoords[0], srcCoords[1], pathLine, srcCoords[0], srcCoords[1], source,
                destCoords[0], destCoords[1], destination);

        Files.write(Paths.get("route_map.html"), html.getBytes());
        System.out.println("Interactive route map generated: route_map.html");
    }
}
