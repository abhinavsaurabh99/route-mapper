package in.abhinavsaurabh;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class TravelPlanner {

    static class City {

        String name;
        double lng, lat;

        City(String name, double lng, double lat) { this.name=name; this.lng=lng; this.lat=lat; }
    }

    static class Route {

        String src, dest;
        double distance, cost;

        Route(String s, String d, double dist, double c) { src=s; dest=d; distance=dist; cost=c; }
    }

    static Map<String, City> cities = new HashMap<>();
    static List<Route> routes = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        // Load CSV files from resources
        loadCities("/cities.csv");
        loadRoutes("/routes.csv");

        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter source city: ");
        String source = scanner.nextLine().trim();
        System.out.print("Enter destination city: ");
        String destination = scanner.nextLine().trim();

        List<String> path = findShortestPath(source, destination);
        if (path == null) {
            System.out.println("No route found.");
            return;
        }

        System.out.println("Route: " + String.join(" -> ", path));

        generateHTML(path, "route.html");

        // Open in browser
        Desktop.getDesktop().browse(Paths.get("route.html").toUri());
    }

    // Load cities.csv from classpath
    static void loadCities(String resourcePath) throws Exception {

        InputStream cityStream = TravelPlanner.class.getResourceAsStream(resourcePath);
        if (cityStream == null) throw new RuntimeException("Could not find " + resourcePath);
        BufferedReader reader = new BufferedReader(new InputStreamReader(cityStream));
        String line;
        reader.readLine(); // skip header
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            cities.put(parts[0], new City(parts[0],
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
        }
    }

    // Load routes.csv from classpath
    static void loadRoutes(String resourcePath) throws Exception {

        InputStream routeStream = TravelPlanner.class.getResourceAsStream(resourcePath);
        if (routeStream == null) throw new RuntimeException("Could not find " + resourcePath);
        BufferedReader reader = new BufferedReader(new InputStreamReader(routeStream));
        String line;
        reader.readLine(); // skip header
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            routes.add(new Route(parts[0], parts[1],
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
        }
    }

    // Dijkstra's algorithm for shortest distance
    static List<String> findShortestPath(String src, String dest) {

        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> visited = new HashSet<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingDouble(dist::get));

        for (String c : cities.keySet()) dist.put(c, Double.MAX_VALUE);
        dist.put(src, 0.0);
        pq.add(src);

        while (!pq.isEmpty()) {
            String u = pq.poll();
            if (visited.contains(u)) continue;
            visited.add(u);

            for (Route r : routes) {
                if (!r.src.equals(u) && !r.dest.equals(u)) continue;
                String v = r.src.equals(u) ? r.dest : r.src;
                double d = dist.get(u) + r.distance;
                if (d < dist.get(v)) {
                    dist.put(v, d);
                    prev.put(v, u);
                    pq.add(v);
                }
            }
        }

        if (!prev.containsKey(dest)) return null;

        List<String> path = new ArrayList<>();
        for (String at = dest; at != null; at = prev.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    // Generate HTML map using Leaflet.js
    static void generateHTML(List<String> path, String filename) throws Exception {

        StringBuilder markers = new StringBuilder();
        StringBuilder lines = new StringBuilder();

        for (String city : path) {
            City c = cities.get(city);
            markers.append(String.format("L.marker([%f,%f]).addTo(map).bindPopup('%s');\n", c.lat, c.lng, c.name));
        }

        for (int i = 0; i < path.size()-1; i++) {
            City a = cities.get(path.get(i));
            City b = cities.get(path.get(i+1));
            lines.append(String.format("L.polyline([[ %f,%f ],[ %f,%f ]], {color:'blue'}).addTo(map);\n",
                    a.lat,a.lng,b.lat,b.lng));
        }

        String center = path.size()>0 ? String.format("%f,%f", cities.get(path.get(0)).lat, cities.get(path.get(0)).lng) : "0,0";

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                <title>Travel Planner Route</title>
                <meta charset="utf-8" />
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>#map { height: 100vh; }</style>
                </head>
                <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map').setView([%s], 6);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        attribution: '© OpenStreetMap contributors'
                    }).addTo(map);
                    %s
                    %s
                </script>
                </body>
                </html>
                """.formatted(center, markers.toString(), lines.toString());

        Files.write(Paths.get(filename), html.getBytes());
    }
}
