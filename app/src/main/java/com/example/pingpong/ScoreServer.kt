package com.example.pingpong

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class ScoreServer(private val context: Context) : NanoHTTPD(8080) {

    var team1Name = "PLAYER 1"
    var team2Name = "PLAYER 2"
    var score1 = 0
    var score2 = 0
    var sets1 = 0
    var sets2 = 0
    var servingPlayer = 0
    var dot1Count = 0
    var dot2Count = 0
    var bestOf = 1
    var winScore = 11
    var gameStarted = false
    var setWinner = ""       // name of set winner, empty if no message
    var matchWinner = ""     // name of match winner, empty if no message

    private var wsServer: ScoreWebSocketServer? = null

    fun startAll() {
        start()
        wsServer = ScoreWebSocketServer(8081)
        wsServer?.start()
    }

    fun stopAll() {
        stop()
        wsServer?.stop()
    }

    fun pushUpdate() {
        wsServer?.broadcast(buildJson())
    }

    private fun buildJson(): String {
        return """{"team1":"$team1Name","team2":"$team2Name","score1":$score1,"score2":$score2,"sets1":$sets1,"sets2":$sets2,"dot1":$dot1Count,"dot2":$dot2Count,"bestOf":$bestOf,"winScore":$winScore,"started":$gameStarted,"setWinner":"$setWinner","matchWinner":"$matchWinner"}"""
    }

    override fun serve(session: IHTTPSession): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            buildHtml()
        )
    }

    private fun buildHtml(): String {
        val formatLabel = when (bestOf) {
            3 -> "B3"
            5 -> "B5"
            else -> "B1"
        }

        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Prat Pong</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            background: #000000;
            color: #FFFFFF;
            font-family: sans-serif;
            height: 100vh;
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }

        /* SPLASH */
        #splash {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            height: 100vh;
        }
        .splash-title {
            font-size: 8vw;
            font-weight: 100;
            letter-spacing: 0.3em;
            color: #FFFFFF;
        }
        .splash-title span { color: #FF8F00; }
        .splash-sub {
            font-size: 1.5vw;
            color: #FF8F00;
            letter-spacing: 0.2em;
            margin-top: 24px;
            opacity: 0.7;
        }
        .splash-dot {
            width: 8px;
            height: 8px;
            background: #FF8F00;
            border-radius: 50%;
            display: inline-block;
            margin: 32px 6px 0 6px;
            animation: pulse 1.2s ease-in-out infinite;
        }
        .splash-dot:nth-child(2) { animation-delay: 0.2s; }
        .splash-dot:nth-child(3) { animation-delay: 0.4s; }
        @keyframes pulse {
            0%, 100% { opacity: 0.2; transform: scale(0.8); }
            50% { opacity: 1; transform: scale(1.2); }
        }

        /* SCOREBOARD */
        #scoreboard { display: none; flex-direction: column; height: 100vh; position: relative; }
        .top-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 24px 48px 8px 48px;
        }
        .name {
            font-size: 2.2vw;
            font-weight: 500;
            border: 1px solid #FF8F00;
            border-radius: 4px;
            padding: 8px 24px;
            flex: 1;
            text-align: center;
            letter-spacing: 0.1em;
        }
        .center-top { width: 200px; }
        .dots-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 4px 48px;
        }
        .dots {
            flex: 1;
            text-align: center;
            color: #4CAF50;
            font-size: 1.4vw;
            letter-spacing: 8px;
            min-height: 1.8vw;
        }
        .dots-space { width: 200px; }
        .middle-row {
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex: 1;
            padding: 0 48px;
        }
        .score {
            font-size: 28vw;
            font-weight: bold;
            flex: 1;
            text-align: center;
            line-height: 1;
        }
        .center-info {
            width: 200px;
            text-align: center;
        }
        .sets {
            font-size: 3vw;
            font-weight: 300;
            margin-bottom: 8px;
        }
        .format {
            font-size: 1.2vw;
            color: #FF8F00;
            opacity: 0.7;
            letter-spacing: 0.1em;
        }

        /* WINNER OVERLAY */
        #winnerOverlay {
            display: none;
            position: absolute;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.92);
            flex-direction: column;
            align-items: center;
            justify-content: center;
            z-index: 10;
            backdrop-filter: blur(4px);
        }
        .winner-label {
            font-size: 1.8vw;
            color: #AAAAAA;
            letter-spacing: 0.2em;
            margin-bottom: 16px;
        }
        .winner-name {
            font-size: 6vw;
            font-weight: 300;
            color: #FF8F00;
            letter-spacing: 0.1em;
        }
        .winner-sub {
            font-size: 1.4vw;
            color: #666666;
            letter-spacing: 0.15em;
            margin-top: 20px;
        }
    </style>
</head>
<body>
    <div id="splash">
        <div class="splash-title">PRAT <span>PONG</span></div>
        <div class="splash-sub">GAME STARTING SOON</div>
        <div>
            <span class="splash-dot"></span>
            <span class="splash-dot"></span>
            <span class="splash-dot"></span>
        </div>
    </div>

    <div id="scoreboard">
        <div class="top-row">
            <div class="name" id="name1">$team1Name</div>
            <div class="center-top"></div>
            <div class="name" id="name2">$team2Name</div>
        </div>
        <div class="dots-row">
            <div class="dots" id="dots1"></div>
            <div class="dots-space"></div>
            <div class="dots" id="dots2"></div>
        </div>
        <div class="middle-row">
            <div class="score" id="score1">$score1</div>
            <div class="center-info">
                <div class="sets" id="sets">$sets1 — $sets2</div>
                <div class="format" id="format">$formatLabel · $winScore</div>
            </div>
            <div class="score" id="score2">$score2</div>
        </div>

        <!-- Winner overlay shown on top of scoreboard -->
        <div id="winnerOverlay">
            <div class="winner-label" id="winnerLabel">SET WON BY</div>
            <div class="winner-name" id="winnerName"></div>
            <div class="winner-sub" id="winnerSub"></div>
        </div>
    </div>

    <script>
        var started = $gameStarted;

        function dotsHtml(count) {
            if (count === 2) return '● ●';
            if (count === 1) return '●';
            return '';
        }

        function updateView(data) {
            document.getElementById('name1').textContent = data.team1;
            document.getElementById('name2').textContent = data.team2;
            document.getElementById('score1').textContent = data.score1;
            document.getElementById('score2').textContent = data.score2;
            document.getElementById('sets').textContent = data.sets1 + ' — ' + data.sets2;
            var fmt = data.bestOf === 3 ? 'B3' : data.bestOf === 5 ? 'B5' : 'B1';
            document.getElementById('format').textContent = fmt + ' · ' + data.winScore;
            document.getElementById('dots1').textContent = dotsHtml(data.dot1);
            document.getElementById('dots2').textContent = dotsHtml(data.dot2);

            if (data.started) {
                document.getElementById('splash').style.display = 'none';
                document.getElementById('scoreboard').style.display = 'flex';
            } else {
                document.getElementById('splash').style.display = 'flex';
                document.getElementById('scoreboard').style.display = 'none';
            }

            var overlay = document.getElementById('winnerOverlay');
            if (data.matchWinner && data.matchWinner !== '') {
                document.getElementById('winnerLabel').textContent = 'MATCH WON BY';
                document.getElementById('winnerName').textContent = data.matchWinner;
                document.getElementById('winnerSub').textContent = 'SETS ' + data.sets1 + ' — ' + data.sets2;
                overlay.style.display = 'flex';
            } else if (data.setWinner && data.setWinner !== '') {
                document.getElementById('winnerLabel').textContent = 'SET WON BY';
                document.getElementById('winnerName').textContent = data.setWinner;
                document.getElementById('winnerSub').textContent = 'SETS ' + data.sets1 + ' — ' + data.sets2;
                overlay.style.display = 'flex';
            } else {
                overlay.style.display = 'none';
            }
        }

        if (started) {
            document.getElementById('splash').style.display = 'none';
            document.getElementById('scoreboard').style.display = 'flex';
        }

        function connect() {
            var host = window.location.hostname;
            var ws = new WebSocket('ws://' + host + ':8081');
            ws.onmessage = function(event) {
                try {
                    var data = JSON.parse(event.data);
                    updateView(data);
                } catch(e) {}
            };
            ws.onclose = function() {
                setTimeout(connect, 2000);
            };
        }

        connect();
    </script>
</body>
</html>
        """.trimIndent()
    }
}

class ScoreWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
    override fun onMessage(conn: WebSocket, message: String) {}
    override fun onError(conn: WebSocket, ex: Exception) {}
    override fun onStart() {}
}