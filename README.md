# GeoMoneyHide

GeoMoneyHide is a location-based Android game where players explore the real world to collect coins and discover new territories.

## 🎮 Game Features

- Explore the real world: every 1 km × 1 km cell you walk into is colored on the map
- Collect gold coins by walking within 20 m of them; discovering a new cell collects the coins inside it
- Spend your coins in the **Marché** (shop):
  - zone colors (and a free hue picker),
  - map themes (Night, Classic, Retro, Neon),
  - 30-minute boosts: **Magnet** (60 m collection radius) and **Double** (coins count ×2)
- Track your explored surface in km², number of zones and percentage of Earth's surface
- Background exploration through a foreground location service

## 📱 Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/KuKaRaCHa-gg/GeoMoneyHide.git
   ```

2. Open the project in **Android Studio**.

3. Configure a Google Maps API key:
   - Create a project in the [Google Cloud Console](https://console.cloud.google.com/).
   - Enable the **Maps SDK for Android**.
   - Generate an API key.
   - Add your API key to `local.properties` (without it the app still builds, using the placeholder from `local.defaults.properties`, but the map stays blank):
     ```
     MAPS_API_KEY=your_api_key_here
     ```

4. Build and run the application on your device.

## 🔑 Setting Up the Google Maps API

1. Visit the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project.
3. Go to **APIs & Services** > **Library**.
4. Enable the following APIs:
   - **Maps SDK for Android**
   - **Places API**
   - **Geocoding API**
   - **Location Services**
5. Navigate to **APIs & Services** > **Credentials** to generate an API key.
6. Restrict the API key to your app’s package name and SHA-1 certificate fingerprint.

## 📚 Project Architecture

| File | Role |
|---|---|
| `GameRepository` | Single source of truth shared by the activity and the background service; debounced atomic saves, listeners |
| `GameState` | Pure game rules: exploration, coins (max 25 active, within 3 km), wallet, shop purchases, boosts |
| `GridMath` | Pure grid math: cell ids `"x:y"`, bounds, neighbours, distances (a position always lies inside its drawn cell) |
| `SaveCodec` | JSON save format (versioned) and migration of old saves |
| `ShopModel` | Shop catalog (`ShopCatalog`), items and purchase results |
| `GridManager` | Draws explored cells on the map (only those near the visible area, max 1500 polygons) |
| `PieceManager` | Coin markers, keyed by stable coin id |
| `LocationTracker` | One location request at a time (foreground / background modes) |
| `LocationService` | Foreground service for background exploration |
| `UIManager` / `MarkerIcons` | Material 3 HUD, animations, shop / color / rules / credits bottom sheets, map styles, coin marker |
| `MapsActivity` | Wires everything together, permissions, map lifecycle |

## 📋 Requirements

- Android 8.0 (API level 26) or higher
- Location permissions (precise location to play; background location optional)
- Google Play Services

## 🧪 Tests

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the grid math, game rules, shop and save migration.

## 🤝 Contributing

1. Fork the repository.
2. Create a new branch (`git checkout -b feature/your-feature-name`).
3. Submit a Pull Request.
