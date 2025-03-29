# GeoMoneyHide

GeoMoneyHide is a location-based Android game where players explore the real world to collect coins and discover new territories.

## 🎮 Game Features

- Explore the real world to discover new areas
- Collect coins scattered across the map
- Track your explored territory in km² and percentage of Earth's surface
- Customize the color of your explored areas
- Background location tracking for continuous play

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
   - Add your API key to `local.properties`:
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

The application follows a modular approach with several key classes:

### 📍 `GridManager`

Handles the grid system for tracking explored territory:

```kotlin
class GridManager(private val gameState: GameState, private val map: GoogleMap)
```

- `GRID_SIZE` - Size of each grid cell (1000m)
- `METERS_PER_DEGREE` - Conversion factor for coordinates to meters
- `getCellId()` - Converts geographic coordinates to a unique grid cell ID
- `getCellBounds()` - Calculates the four corners of a given cell
- `drawExploredCell()` - Draws a cell on the map when explored
- `updateCellColors()` - Updates colors of all cells when the user changes the color
- `calculateExploredSurface()` - Calculates the total area explored

### 💰 `PieceManager`

Manages coin generation and collection:

```kotlin
class PieceManager(private val context: MapsActivity, private val gameState: GameState, 
                  private val gridManager: GridManager, private val map: GoogleMap?)
```

- `spawnPiecesAroundUser()` - Generates coins near the player’s location
- `checkCollectedPieces()` - Checks if the player is close enough to collect coins
- `collectAllPiecesInCell()` - Collects all coins in a newly explored cell
- `adjustPieceCount()` - Maintains the correct number of coins based on explored area

### 🗺️ `LocationService`

Background service that tracks the player’s location:

```kotlin
class LocationService : Service()
```

- Runs as a foreground service with a persistent notification
- Continuously tracks location even when the app is in the background
- Broadcasts location updates to the main app

### 🎮 `GameState`

Stores and manages all game data:

```kotlin
class GameState(private val context: Context)
```

- Tracks explored cells, collected coins, and player positions
- Manages color preferences for explored areas
- Handles data persistence with **SharedPreferences**

### 🎨 `UIManager`

Manages all user interface elements:

```kotlin
class UIManager(private val context: MapsActivity, private val gameState: GameState, 
               private val gridManager: GridManager)
```

- `showColorPickerDialog()` - Allows customization of explored area colors
- `showRulesDialog()` - Displays game rules
- `showCreditsDialog()` - Shows app credits and information

### 🏁 `MapsActivity`

Main activity that ties everything together:

```kotlin
class MapsActivity : AppCompatActivity(), OnMapReadyCallback
```

- Initializes the map and all managers
- Handles permission requests
- Manages the activity lifecycle
- Processes location updates and updates the UI

## 📋 Requirements

- Android 6.0 (API level 23) or higher
- Location permissions
- Google Play Services
- Internet connection

## 📝 Key Implementation Details

- The world is divided into 1km × 1km grid cells.
- Each explored cell is saved with a unique ID in the format "x:y".
- The app calculates and displays the percentage of Earth’s surface explored.
- Location tracking continues in the background using a **Foreground Service**.
- Coins are generated at a rate of approximately 3 per km² of explored area.

## 🤝 Contributing

Contributions are welcome! Please follow these steps:
1. Fork the repository.
2. Create a new branch (`git checkout -b feature/your-feature-name`).
3. Submit a Pull Request.





### Notes :
- J’ai repris votre contenu tel quel, en m’assurant qu’il est bien formaté pour GitHub.
- Si vous voulez ajouter des sections (comme une capture d’écran, des instructions supplémentaires ou une FAQ), faites-le-moi savoir !
- Si vous préférez une version en français, je peux la traduire intégralement.

Qu’en pensez-vous ? Souhaitez-vous des ajustements ?
