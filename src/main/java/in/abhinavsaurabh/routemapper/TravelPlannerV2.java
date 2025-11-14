package in.abhinavsaurabh.routemapper;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import org.json.*;

/**
 * TravelPlanner — Uses OSM (OpenStreetMap) + OSRM API to generate
 * a real driving route between two cities and display it on an interactive map.
 */
public class TravelPlannerV2
{
    public static void main(String[] args) throws Exception
    {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter source city: ");
        String source = sc.nextLine().trim();
        System.out.print("Enter destination city: ");
        String destination = sc.nextLine().trim();
        double[] srcCoords = getLatLng(source);
        double[] destCoords = getLatLng(destination);
        if (srcCoords == null || destCoords == null)
        {
            System.out.println("Could not find one of the cities. Please check spelling.");
            return;
        }
        JSONObject routeData = getRouteFromOSM(srcCoords, destCoords);
        if (routeData == null)
        {
            System.out.println("No route found.");
            return;
        }
        List<double[]> routeCoords = parseRouteCoords(routeData);
        double distanceKm = routeData.getDouble("distance") / 1000.0;
        double durationHr = routeData.getDouble("duration") / 3600.0;
        double cost = distanceKm * 10.0;

        // Extract intermediate city markers
        List<String[]> midCities = extractIntermediateCities(routeCoords);

        generateHTML(source, destination, srcCoords, destCoords, routeCoords,
                midCities, distanceKm, durationHr, cost, "route.html");

        System.out.printf("Route map generated: route.html (%.2f km, %.2f hr, ₹%.2f)%n",
                distanceKm, durationHr, cost);

        Desktop.getDesktop().browse(Paths.get("route.html").toUri());
    }

    // Get coordinates of a place using Nominatim API
    public static double[] getLatLng(String place)
    {
        try
        {
            String urlStr = String.format(
                    "https://nominatim.openstreetmap.org/search?q=%s&format=json&limit=1",
                    URLEncoder.encode(place, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java App)");
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
            reader.close();
            JSONArray arr = new JSONArray(sb.toString());
            if (!arr.isEmpty())
            {
                JSONObject o = arr.getJSONObject(0);
                double lat = o.getDouble("lat");
                double lon = o.getDouble("lon");
                System.out.printf("%s → (%.5f, %.5f)%n", place, lat, lon);
                return new double[]{lat, lon};
            }
        }
        catch (Exception e)
        {
            System.err.println("Error fetching lat/lng for " + place + ": " + e.getMessage());
        }
        return null;
    }

    // Fetch route data from OSRM (includes geometry + metadata)
    public static JSONObject getRouteFromOSM(double[] src, double[] dest)
    {
        try
        {
            String urlStr = String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                    src[1], src[0], dest[1], dest[0]);
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java App)");
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
            reader.close();
            JSONObject json = new JSONObject(sb.toString());
            JSONArray routes = json.getJSONArray("routes");
            if (!routes.isEmpty())
            {
                JSONObject route = routes.getJSONObject(0);
                JSONObject geometry = route.getJSONObject("geometry");
                JSONObject result = new JSONObject();
                result.put("geometry", geometry);
                result.put("distance", route.getDouble("distance"));
                result.put("duration", route.getDouble("duration"));
                return result;
            }
        }
        catch (Exception e)
        {
            System.err.println("Error fetching route: " + e.getMessage());
        }
        return null;
    }

    // Extract coordinates from route JSON
    public static List<double[]> parseRouteCoords(JSONObject routeData)
    {
        List<double[]> coords = new ArrayList<>();
        JSONArray geometry = routeData.getJSONObject("geometry").getJSONArray("coordinates");
        for (int i = 0; i < geometry.length(); i++)
        {
            JSONArray pt = geometry.getJSONArray(i);
            coords.add(new double[]{pt.getDouble(1), pt.getDouble(0)}); // lat, lon
        }
        return coords;
    }

    // Approximate intermediate cities by sampling route points
    public static List<String[]> extractIntermediateCities(List<double[]> route)
    {
        List<String[]> mid = new ArrayList<>();
        try
        {
            for (int i = 10; i < route.size(); i += Math.max(30, route.size() / 10))
            {
                double[] p = route.get(i);
                String urlStr = String.format(Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?lat=%f&lon=%f&format=json",
                        p[0], p[1]);
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java App)");
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null)
                {
                    sb.append(line);
                }
                r.close();
                JSONObject resp = new JSONObject(sb.toString());
                if (resp.has("address"))
                {
                    JSONObject addr = resp.getJSONObject("address");
                    String name = addr.has("city") ? addr.getString("city")
                            : addr.has("town") ? addr.getString("town")
                            : addr.has("village") ? addr.getString("village") : null;
                    if (name != null && mid.stream().noneMatch(x -> x[0].equals(name)))
                    {
                        mid.add(new String[]{name, String.valueOf(p[0]), String.valueOf(p[1])});
                    }
                }
                Thread.sleep(500); // be polite to API
            }
        }
        catch (Exception e)
        {
            System.err.println("Error fetching mid cities: " + e.getMessage());
        }
        return mid;
    }

    /**
     * Generate Leaflet HTML showing the route, start/end, and mid cities.
     */
    public static void generateHTML(String srcName, String destName, double[] src, double[] dest,
                                    List<double[]> route, List<String[]> midCities,
                                    double distance, double duration, double cost,
                                    String filename) throws Exception
    {
        StringBuilder poly = new StringBuilder("[");
        for (double[] p : route)
        {
            poly.append(String.format("[%f,%f],", p[0], p[1]));
        }
        if (poly.charAt(poly.length() - 1) == ',')
        {
            poly.setLength(poly.length() - 1);
        }
        poly.append("]");
        StringBuilder midMarkers = new StringBuilder();
        for (String[] c : midCities)
        {
            midMarkers.append(String.format(
                    "L.circleMarker([%s,%s],{color:'orange'}).addTo(map).bindPopup('%s');\n",
                    c[1], c[2], c[0]));
        }
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>Travel Route</title>
                    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
                    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                    <style>#map{height:100vh;}</style>
                </head>
                <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map').setView([%f,%f],6);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
                        attribution:'© OpenStreetMap contributors'
                    }).addTo(map);
                    var line = L.polyline(%s,{color:'blue',weight:5}).addTo(map);
                    map.fitBounds(line.getBounds());

                    L.marker([%f,%f]).addTo(map).bindPopup('<b>Start: %s</b>');
                    L.marker([%f,%f]).addTo(map).bindPopup('<b>Destination: %s</b>');

                    %s

                    L.control.scale().addTo(map);
                    L.control.layers({}, {
                        'Route Info': L.popup().setLatLng([%f,%f]).setContent(
                            '<b>Distance:</b> %.2f km<br><b>Duration:</b> %.2f hr<br><b>Cost:</b> ₹%.2f'
                        ).openOn(map)
                    }).addTo(map);
                </script>
                </body>
                </html>
                """.formatted(
                src[0], src[1],
                poly.toString(),
                src[0], src[1], srcName,
                dest[0], dest[1], destName,
                midMarkers.toString(),
                (src[0] + dest[0]) / 2, (src[1] + dest[1]) / 2,
                distance, duration, cost
        );
        Files.write(Paths.get(filename), html.getBytes());
    }
}
