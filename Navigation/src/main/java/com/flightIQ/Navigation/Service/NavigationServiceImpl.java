
package com.flightIQ.Navigation.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightIQ.Navigation.DTO.*;

import java.time.Instant;
import java.util.Optional;
import com.fasterxml.jackson.core.type.TypeReference;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import com.flightIQ.Navigation.Models.Airport;
import com.flightIQ.Navigation.Models.FIXX;
import com.flightIQ.Navigation.Models.Restaurant;
import com.flightIQ.Navigation.Models.WindsAloftClient;
import com.flightIQ.Navigation.Repository.AirportRepository;
import com.flightIQ.Navigation.Repository.FIXXRepository;
import com.flightIQ.Navigation.Exceptions.AirportNotFoundException;
import com.flightIQ.Navigation.Exceptions.FixxNotFoundException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class NavigationServiceImpl implements Navigation_svc {

       

    private final Map<Integer, Integer> compassDeviationTable = Map.ofEntries(
        Map.entry(0, 0),
        Map.entry(30, 1),
        Map.entry(60, 4),
        Map.entry(90, 6),
        Map.entry(120, 4),
        Map.entry(150, 3),
        Map.entry(180, 2),
        Map.entry(210, 0),
        Map.entry(240, 0),
        Map.entry(270, 0),
        Map.entry(300, 0),
        Map.entry(330, -1)
    );




    @Autowired
	private AirportRepository airportRepository;
	
	@Autowired
	private FIXXRepository fixxRepository;

    private WindsAloftClient Windclient;


    private static final String ENDPOINT_OPENSKY = "https://opensky-network.org/api";
    private static final String ENDPOINT_OPENSKY_AUTH = "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";


    private static final ObjectMapper OBJ_MAPPER = new ObjectMapper(); // Maps StateVectors from API to a List
    private final RestTemplate _restTemplate = new RestTemplate();
    private final Logger _logger = LoggerFactory.getLogger(NavigationServiceImpl.class);

    private static final float[] US_BOUNDING_BOX = new float[] {24.5f, -125.0f, 49.5f, -66.9f}; // {lamin, lomin, lamax, lomax}

    @Value("${opensky.client-id}")
    private String clientId;

    @Value("${opensky.client-secret}")
    private String clientSecret;

    private AccessToken openskyToken;

    @Value("${geoapify-secret}")
    private String geoApifySecret;

    public NavigationServiceImpl(WindsAloftClient windsAloftClient) {
        this.Windclient = windsAloftClient;
    }


	public void AirportService(AirportRepository airportRepository) {
        this.airportRepository = airportRepository;
    }
	
	public void FIXXService(FIXXRepository fixxRepository) {
		this.fixxRepository = fixxRepository;
	}


    /********************************************
     * ENDPOINTS
     ******************************************************/



    private void configureAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = _restTemplate.postForEntity(ENDPOINT_OPENSKY_AUTH, request, Map.class);

        // Access Token expires after 30 minutes. So reconfigure after 28 minutes.
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            String accessToken = (String) response.getBody().get("access_token");
            openskyToken = new AccessToken(accessToken, Instant.now().plusSeconds(1680));
        } else {
            _logger.error("configureAccessToken: Failed to obtain AccessToken.");
        }
    }


    private String fetchGeoapifyPlaces(double lat, double lon) {
        
        String categories = "catering";
        int radius = 1609; // default radius in meters (adjustable)

        String url = String.format(
            "https://api.geoapify.com/v2/places?categories=%s&filter=circle:%.6f,%.6f,%d&bias=proximity:%.6f,%.6f&apiKey=%s",
            categories, lon, lat, radius, lon, lat, geoApifySecret
        );

       
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(headers);


        ResponseEntity<String> response = _restTemplate.exchange(
            url,
            HttpMethod.GET,
            entity,
            String.class
        );

        // Return JSON body or handle error
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return response.getBody();
        } else {
            throw new RuntimeException("Geoapify API request failed: " + response.getStatusCode());
        }
    }


    private StateVector[] parseRawVectors(String rawJson) {
        try {
            Map<String, Object> map = OBJ_MAPPER.readValue(rawJson, new TypeReference<>() {});
            List<List<Object>> rawStates = (List<List<Object>>) map.get("states");

            if (rawStates == null) return new StateVector[0];
            return rawStates.stream().map(StateVector::fromList).toArray(StateVector[]::new);

        } catch (JsonProcessingException e) {
            _logger.error(">>>Error Processing Raw Vectors<<<", e);
            return null;
        }
    }

    @Override
    public List<Restaurant> getNearbyRestaurants(String icaoCode, String identCode, Double lat, Double lon) {
        Airport airport = null;

        if (icaoCode != null && !icaoCode.isEmpty()) {
            airport = getAirportFromICAO(icaoCode.toUpperCase());
        } else if (identCode != null && !identCode.isEmpty()) {
            airport = getAirportFromIDENT(identCode.toUpperCase());
        }

        if (airport != null) {
            lat = airport.getLatitude();
            lon = airport.getLongitude();
        }

  
        if (lat == null || lon == null) {
            throw new IllegalArgumentException("Either ICAO, identCode, or lat/lon must be provided.");
        }


        String jsonResponse = fetchGeoapifyPlaces(lat, lon);
        JSONObject json = new JSONObject(jsonResponse);
        JSONArray features = json.getJSONArray("features");

        List<Restaurant> restaurants = new ArrayList<>();

       
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject props = feature.getJSONObject("properties");

            if (props.has("name")) {
                String name = props.getString("name");
                JSONObject geometry = feature.getJSONObject("geometry");
                JSONArray coords = geometry.getJSONArray("coordinates");

                double lonVal = coords.getDouble(0);
                double latVal = coords.getDouble(1);

                restaurants.add(new Restaurant(name, latVal, lonVal));
            }
        }

        return restaurants;
    }

    @Override
    public StateVector[] getStateVectors(float lamin, float lomin, float lamax, float lomax) {
        // Check Token
        if (openskyToken == null || openskyToken.expired())
            configureAccessToken();

        // Build URI
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(ENDPOINT_OPENSKY+"/states/all");
        uriBuilder.queryParam("lamin", lamin);
        uriBuilder.queryParam("lomin", lomin);
        uriBuilder.queryParam("lamax", lamax);
        uriBuilder.queryParam("lomax", lomax);

        // Build request
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openskyToken.getToken());
        HttpEntity<Void> request = new HttpEntity<>(headers);

        // Send Endpoint output
        return parseRawVectors(_restTemplate.exchange(uriBuilder.toUriString(), HttpMethod.GET, request, String.class).getBody());
    }


    @Override
    public StateVector[] getStateVectorsUS() {
        return getStateVectors(US_BOUNDING_BOX[0], US_BOUNDING_BOX[1], US_BOUNDING_BOX[2], US_BOUNDING_BOX[3]);
    }



    @Override
    public String GetATISOFDestination(String X_coord, String Y_coord, String DestAirportCode) {
        // TODO Auto-generated method stub

        // System.out.println(ComputeTrueCourse(0, DestAirportCode,
        // 26.2473600,-80.1111272 ));
        String response = "";
        return response;
    }


    @Override
    public Airport getAirportFromIDENT(String identCode) {
    	Airport airport = airportRepository.findByIdent(identCode)
    			.orElseThrow(() -> new AirportNotFoundException("Airport does not exist with IDENT: " + identCode));
    	
    	return airport;
    }

	@Override
	public Airport getAirportFromICAO(String icaoCode) {
		Airport airport = airportRepository.findByIcao(icaoCode)
						  .orElseThrow(() -> new AirportNotFoundException("Airport does not exist with ICAO: " + icaoCode));
		
		return airport;
	}
    
	@Override
	public FIXX getFIXXFromId(String fixxId) {
		FIXX fixx = fixxRepository.findByFixxId(fixxId)				
					.orElseGet(() -> {
						if (fixxId.charAt(0) == '(' && fixxId.charAt(fixxId.length() - 1) == ')') {
							FIXX fixx1 = new FIXX();
							fixx1.setFixxId(fixxId);
		            		String[] formattedCoordinates = (fixxId.substring(1,fixxId.length() - 1)).split(",");
		            		fixx1.setLatitude(Double.parseDouble(formattedCoordinates[0]));
		            		fixx1.setLongitude(Double.parseDouble(formattedCoordinates[1]));
		            		return fixx1;
						}
						else {
							throw new FixxNotFoundException("FIXX not found with ID: " + fixxId);
						}
					});
		
		return fixx;
	}
    @Override
    public String computeNavlog(String route, String aircraft, String cruiseALT, String TAS) {
        // TODO Auto-generated method stub
    
        // KIMM (26.2241,-81.3186) (26.2233,-80.4911) (26.2407,-80.2758) KPMP test data
        // point
        // http://localhost:8080/api/v1/ComputeNavlog?route=KIMM%20(26.2241,-81.3186)%20(26.2233,-80.4911)%20(26.2407,-80.2758)%20KPMP&aircraft=PA-28-151&CruiseALT=4500&TAS=118
    
        List<RouteNode> flightroute = prepareRouteObject(route);
    
        System.out.println(flightroute);
    
        double totalFuelBurn = 1.5;
        double totalETE = 0;
        double totalDistance = 0; 
    
        List<Double> runningTotalFuelBurn = new ArrayList<>();
        List<String> runningTotalETE = new ArrayList<>();

    WindAloft originWinds = getWindsAoft(flightroute.get(0).getNodeName(), Integer.parseInt(cruiseALT));
    System.out.println("HAVE WINDS FOR" + originWinds );

    WindAloft destinationWinds = getWindsAoft(flightroute.get(flightroute.size() - 1).getNodeName(), Integer.parseInt(cruiseALT));
    System.out.println("HAVE WINDS FOR" + destinationWinds );

    // Calculate the average wind data for use throughout the flight

    int avgDirection = (int) Math.round((originWinds.getDirection() + destinationWinds.getDirection()) / 2.0);

    int avgSpeed = (int) Math.round((originWinds.getSpeed() + destinationWinds.getSpeed()) / 2.0);

    String avgwinds = avgDirection + "@" + avgSpeed;


    System.out.println("USING AVG WINDS OF " + avgwinds);



    
        for (int i = 0; i < flightroute.size(); i++) {
            RouteNode curr = flightroute.get(i);
    
            String phaseOfFlight = (i == 0) ? "CLB" : (i == flightroute.size() - 1) ? "DES" : "CRZ";
    
            int course = (int) curr.getBearing();
    
            System.out.println();
    
            double groundspeed = Double.parseDouble(
                    ComputeTrueCourseAndGroundsped(course, avgwinds, Integer.parseInt(TAS)).split("-")[1]);
            int truecourse = Integer.parseInt(ComputeTrueCourseAndGroundsped(course, avgwinds, Integer.parseInt(TAS)).split("-")[0]);
            System.out.println("CHANGING " + curr.getBearing() + " TO " + truecourse);
            
            curr.setBearing(truecourse);
           
            double dist = curr.getDistance();
            double timeForLeg = computeTimeForLeg(groundspeed, dist);
    
            double legFuelBurn = computeFuelBurnForLeg(getAircraftFromDB(aircraft), dist, timeForLeg, phaseOfFlight);
    
            totalETE += timeForLeg;
            totalFuelBurn += legFuelBurn;
            totalDistance += dist;
   
    
            System.out.println("TIME FOR LEG " + timeForLeg + " leg dist " + dist);
            System.out.println("FUEL BURNED " + legFuelBurn);

            runningTotalFuelBurn.add(totalFuelBurn);
            runningTotalETE.add(formatTime(totalETE));

        }
    
        String formattedETE = formatTime(totalETE);
    
        System.out.println("FLIGHTROUTE OBJ " + flightroute.toString());

        runningTotalETE.remove(runningTotalETE.size() -1);
        runningTotalFuelBurn.remove(runningTotalFuelBurn.size() -1 );


    
        return "Distance " + truncate(totalDistance) + "^Total ETE: " + formattedETE + "^Total Fuel Burn: " + truncate(totalFuelBurn) + "gallons^" + runningTotalETE + "^" + runningTotalFuelBurn +"^" + flightroute.toString();
    }

    private String formatTime(double totalHours) {
        int hours = (int) totalHours;
        int minutes = (int) ((totalHours - hours) * 60);

        return String.format("%02d:%02d", hours, minutes);
    }

    private double truncate(double value) {
        return Math.floor(value * 100) / 100;
    }

    /***************************************
     * HELPER FUNCTIONS
     ******************************************************/

     public WindAloft getWindsAoft(String ICAO, int altitude) {
        System.out.println("GETTING WINDS FOR " + ICAO + " ALTITUDE " + altitude);
    
        WindAloft wind = Windclient.getWindsAloftByIcao(ICAO, altitude);
    
        if (wind == null) {
            System.out.println("Wind data is null. Returning default 000@0.");
            WindAloft defaultWind = new WindAloft();
            defaultWind.setDirection(0);
            defaultWind.setSpeed(0);
            defaultWind.setClosestAirportCode(ICAO);
            defaultWind.setDistanceFromOriginalAirportInMiles(0.0);
            return defaultWind;
        }
    
        // Additional safety: treat variable wind (0 deg and 0 speed) as default too
        if (wind.getDirection() == 0 && wind.getSpeed() == 0) {
            System.out.println("Variable or calm wind detected. Normalized to 000@0.");
        }
    
        return wind;
    }
    


    
    public List<RouteNode> prepareRouteObject(String routeString) {
        String[] points = routeString.split(" ");
        ArrayList<RouteNode> flightRoute = new ArrayList<>();
    
        // Handle simple direct airport-to-airport route
        if (points.length == 2) {
            Airport departureAirport = getAirportFromICAO(points[0]);
            Airport arrivalAirport = getAirportFromICAO(points[1]);
    
            double bearing = computeBearing(
                departureAirport.getLatitude(), departureAirport.getLongitude(),
                arrivalAirport.getLatitude(), arrivalAirport.getLongitude()
            );
            double distance = computeDistance(
                departureAirport.getLatitude(), departureAirport.getLongitude(),
                arrivalAirport.getLatitude(), arrivalAirport.getLongitude()
            );
    
            flightRoute.add(new RouteNode(departureAirport.getIcao(), bearing, distance));
            flightRoute.add(new RouteNode(arrivalAirport.getIcao(), 0.0, 0.0));
            return flightRoute;
        }
    
        for (int i = 0; i < points.length - 1; i++) {
            LatLon from = getLatLon(points[i]);
            LatLon to = getLatLon(points[i + 1]);
    
            double bearing = computeBearing(from.lat, from.lon, to.lat, to.lon);
            double distance = computeDistance(from.lat, from.lon, to.lat, to.lon);
    
            String fromId = getIdentifier(points[i]);
            flightRoute.add(new RouteNode(fromId, bearing, distance));
        }
    
        // Add the final point with 0.0 values
        String finalId = getIdentifier(points[points.length - 1]);
        flightRoute.add(new RouteNode(finalId, 0.0, 0.0));
    
        return flightRoute;
    }
    

 

        // Represents a parsed lat/lon coordinate
    private static class LatLon {
        public double lat;
        public double lon;
        public LatLon(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private LatLon getLatLon(String point) {
        if (point.matches("\\(.*?,.*?\\)")) {
            String[] coord = point.replace("(", "").replace(")", "").split(",");
            return new LatLon(Double.parseDouble(coord[0]), Double.parseDouble(coord[1]));
        } else if (isICAO(point)) {
            Airport airport = getAirportFromICAO(point);
            return new LatLon(airport.getLatitude(), airport.getLongitude());
        } else {
            FIXX fixx = getFIXXFromId(point);
            return new LatLon(fixx.getLatitude(), fixx.getLongitude());
        }
    }

    private String getIdentifier(String point) {
        if (point.matches("\\(.*?,.*?\\)")) {
            return point; // keep raw lat/lon string
        } else if (isICAO(point)) {
            return point; // ICAO code
        } else {
            return point; // fix ID
        }
    }

    private boolean isICAO(String point) {
        return point.length() == 4 && point.matches("[A-Z]{4}");
    }


    private double computeBearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
                Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private double computeDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 3440; // Radius of the Earth in nautical miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c; // Distance in nautical miles
    }

    public String ComputeTrueCourseAndGroundsped(int plottedCourse, String WindsAloftAtCruise, int TAS) {
        int windHeading = Integer.parseInt(WindsAloftAtCruise.split("@")[0]);
        int windspeed = Integer.parseInt(WindsAloftAtCruise.split("@")[1]);
    
        double groundSpeed = computeGroundSpeed(TAS, windspeed, plottedCourse, windHeading);
    
        double angleDiff = ((windHeading - plottedCourse + 540) % 360) - 180;
        double angleDiffRad = Math.toRadians(angleDiff);
    
        double sinWCA = (windspeed * Math.sin(angleDiffRad)) / TAS;
        sinWCA = Math.max(-1.0, Math.min(1.0, sinWCA));  // clamp to avoid asin domain errors
    
        double WCA = Math.toDegrees(Math.asin(sinWCA));
        int truecourse = (int) ((plottedCourse + WCA + 360) % 360);
    
        // === Compass deviation adjustment ===
        Map<Integer, Integer> deviationTable = compassDeviationTable;
    
        // Round to the nearest key (typically in 30° increments)
        int rounded = ((truecourse + 15) / 30) * 30 % 360;
    
        // Apply deviation adjustment
        int deviation = deviationTable.getOrDefault(rounded, 0);
        int adjustedTrueCourse = (truecourse + deviation + 360) % 360;
    
        return adjustedTrueCourse + "-" + Math.round(groundSpeed);
    }
    
    

 

    public static double computeGroundSpeed(double airspeed, double windSpeed, double course, double windDirection) {
        // Convert course and wind direction from degrees to radians
        double courseInRadians = Math.toRadians(course);
        double windDirectionInRadians = Math.toRadians(windDirection);
        double angle = windDirectionInRadians - courseInRadians;
        double windComponent = windSpeed * Math.cos(angle);
        double groundSpeed = airspeed - windComponent; // Subtracting because windComponent is positive for headwind

        return groundSpeed;
    }

    private double computeFuelBurnForLeg(Aircraft aircraft, double distance, double time, String legType) {

        double fuelBurn = 0.0;
        double galsPerMin = aircraft.getCRZfuelBurn() / 60;

        switch (legType.toUpperCase()) {
            case "CLB":
                fuelBurn = (aircraft.getCLBFuelBurn() / 60) * time;
                break;
            case "CRZ":
                fuelBurn = (aircraft.getCRZfuelBurn() / 60) * time;
                break;
            case "DES":
                fuelBurn = (aircraft.getDescFuelbURN() / 60) * time;
                break;
            default:
                throw new IllegalArgumentException("Invalid leg type: " + legType);
        }

        return fuelBurn;
    }

    private double computeTimeForLeg(double groundspeed, double distance) {

        double nautical_miles_per_min = groundspeed / 60;
        return distance / nautical_miles_per_min;
    }

    public static Aircraft getAircraftFromDB(String ac) {
        return AircraftDB.getAircraftFromDB(ac);
    }

}
