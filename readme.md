# 🏓 Prat Pong

A sleek, modern ping pong scoreboard app for Android. Designed to be used flat on a table during a game, with a live scoreboard that streams to your TV via your home Wi-Fi.

---

<img src="https://github.com/user-attachments/assets/be217178-a58d-4242-a959-26964840d87f" alt="Mobile_Scoreboard-1" width="100%" />

---

## Features

- **2 or 4 player support** — singles or doubles
- **Configurable match format** — Best of 1, 3, or 5 sets
- **Win condition** — First to 11 or 21 points (with 2-point lead rule)
- **Serve tracking** — Green dots show who serves, switching automatically every 2 points (every 1 at deuce)
- **Swap sides** — Swap players, scores, sets and serve in one tap
- **Editable player names** — Tap a name to edit it during the game
- **TV Scoreboard** — Stream the live score to any browser on your TV via Wi-Fi (WebSocket, instant updates)
- **Splash screen on TV** — Shows "Game Starting Soon" while on the setup page
- **Set & match winner overlays** — Displayed both in-app and on the TV scoreboard
- **Remembers last settings** — Player names and match format are saved between sessions
- **Pure AMOLED design** — Black background, amber accents, optimized for landscape

---

## How to Use

### Setup Screen
1. Select number of players (2 or 4)
2. Select win condition (11 or 21 points)
3. Select match format (Best of 1, 3 or 5)
4. Tap **START →**

### Game Screen
- **Tap the score number** to add a point
- **Tap −1** to remove a point
- **Tap a player name** to edit it
- **Tap ⇄** to swap sides (scores, sets and serve switch too)
- **Tap the green dots** to manually set who serves
- At the start of each set a popup asks who serves first
- Tap **NEW SET** to start a new set (confirmation required if scores are not 0)
- Tap **RESTART** to reset the full match
- Tap **SETUP** to go back to the setup screen
- Tap **ⓘ** for help and TV scoreboard instructions

### TV Scoreboard
1. Make sure your phone and TV are on the same Wi-Fi network
2. Open the browser on your TV
3. Type the IP address shown in the **ⓘ Help** screen: `http://[phone-ip]:8080`
4. The scoreboard updates instantly when points are scored

---

## Screenshots

### Mobile
<img src="https://github.com/user-attachments/assets/10f7d23a-a65f-44d1-ab41-e39602d5cdd9" alt="Mobile_Setup" width="100%" />
<img src="https://github.com/user-attachments/assets/a7455b5a-2fdb-49ec-beee-20c024987583" alt="Mobile_New_Set_Serve" width="100%" />
<img src="https://github.com/user-attachments/assets/2bb2f720-4f16-458a-88c9-94667893001a" alt="Mobile_Scoreboard-2" width="100%" />
<img src="https://github.com/user-attachments/assets/cf079dbb-e5e3-4ba7-b99e-17a3b27548d3" alt="Mobile_Information_2" width="100%" />

### Live Stream (local wifi)
<img src="https://github.com/user-attachments/assets/61f6f319-1ec7-4a44-85ce-68432b9862b7" alt="WebPage_0" width="100%" />
<img src="https://github.com/user-attachments/assets/7389f0ce-e5e2-461b-8d63-6006a3c3aeab" alt="WebPage_1" width="100%" />
<img src="https://github.com/user-attachments/assets/04edb851-e641-41e6-9a7d-73faf0c7177d" alt="WebPage_2" width="100%" />


---

## Technical Details

- **Language:** Kotlin
- **Min SDK:** API 26 (Android 8.0)
- **Architecture:** Two activities (SetupActivity, GameActivity)
- **Local web server:** NanoHTTPD on port 8080 (serves the TV scoreboard HTML page)
- **WebSocket server:** Java-WebSocket on port 8081 (pushes instant score updates to the TV)
- **Orientation:** Landscape only

### Dependencies
```kotlin
implementation("org.nanohttpd:nanohttpd:2.3.1")
implementation("org.java-websocket:Java-WebSocket:1.5.4")
```

---

## Building

1. Clone the repository
2. Open in Android Studio
3. Build and run on your Android device (API 26+)
4. No Play Store account or signing required for personal use

---

## Installation on your phone (without Play Store)

1. Build the project in Android Studio
2. Enable **Developer Options** on your phone (tap Build Number 7 times in Settings → About Phone)
3. Enable **USB Debugging** in Developer Options
4. Connect your phone via USB
5. Press **Run** in Android Studio

---

## License

Personal use only. Not published on the Google Play Store.
