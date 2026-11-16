package ua.hapanel;

/** Example entity mapping. Replace these values with the entity IDs from your installation. */
final class PanelConfig {
    static final String TIME_ZONE = "Europe/Kiev";
    static final String ALWAYS_ON_OUTLET = "switch.example_always_on_outlet";
    static final String WEATHER = "weather.example_home";
    static final String TEMPERATURE = "sensor.example_temperature";
    static final String HUMIDITY = "sensor.example_humidity";
    static final String CALENDAR = "calendar.example_power_outages";
    static final String SCHEDULE_UPDATED = "sensor.example_outage_schedule_updated";
    static final String CAMERA = "camera.example_front_door";
    static final String CAMERA_RTSP = "rtsp://home-assistant.example:18766/street";
    static final String[] STATION_NAMES = {"АКБ 1", "АКБ 2"};
    static final String[] STATION_ENTITIES = {
        "sensor.example_station_1_charge", "sensor.example_station_2_charge",
        "sensor.example_station_1_output_power", "sensor.example_station_2_output_power",
        "binary_sensor.example_station_1_on_grid", "sensor.example_station_2_input_power"
    };

    static final MainActivity.Room[] ROOMS = {
        new MainActivity.Room("Вітальня", RoomIcon.SOFA,
            new MainActivity.Light("Світло", "light.example_living_room")),
        new MainActivity.Room("Кухня", RoomIcon.KITCHEN,
            new MainActivity.Light("Світло", "light.example_kitchen")),
        new MainActivity.Room("Спальня", RoomIcon.BED,
            new MainActivity.Light("Світло", "light.example_bedroom")),
        new MainActivity.Room("Коридор", RoomIcon.DOOR,
            new MainActivity.Light("Підсвітка", "switch.example_hall_light")),
        new MainActivity.Room("Ванна", RoomIcon.MIRROR,
            new MainActivity.Light("Дзеркало", "switch.example_mirror")),
        new MainActivity.Room("Панель", RoomIcon.CHARGE,
            new MainActivity.Light("Зарядка", ALWAYS_ON_OUTLET))
    };

    private PanelConfig() {}
}
