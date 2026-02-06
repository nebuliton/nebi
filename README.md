# 🤖 Nebi

Ein frecher Discord-Bot mit Persönlichkeit. Gebaut mit JDA, OpenAI API und SQLite.

> *"Bro was"* – Nebi, wahrscheinlich

---

## ✨ Features

### 💬 Chat
- **@Mention = Antwort** – Einfach den Bot pingen und er antwortet
- **Konversations-Memory** – Merkt sich die letzten Nachrichten pro User
- **Persönlichkeit** – Redet wie ein echter Mensch, nicht wie ein Kundenservice-Bot

### 🧠 Auto-Learning
- **Lernt selbstständig** – Wenn User ihm Fakten erzählen, speichert er sie automatisch
- **Fact-Checking** – Jeder Fakt wird geprüft bevor er gespeichert wird
- **Filter** – Lehnt politische, kontroverse oder falsche Aussagen ab

### 🛠️ Management
- **User-Kontext** – Speichere Infos über User (Vorlieben, Running Gags)
- **Server-Wissen** – Globales Wissen das die AI nutzen kann
- **Blacklist** – Blocke User für AI-Antworten

---

## 🚀 Setup

### Voraussetzungen
- Java 21+
- Discord Bot Token (mit **Message Content Intent**)
- OpenAI API Key

### Installation

```bash
# 1. Config erstellen
cp config/config.example.yml config/config.yml

# 2. config.yml ausfüllen (Token, API-Key, etc.)

# 3. Bauen
mvn -DskipTests package

# 4. Starten
java -jar target/Nebi-1.0.jar
```

Optional mit eigener Config:
```bash
java -jar target/Nebi-1.0.jar pfad/zur/config.yml
```

---

## 📋 Commands

### Context (User-spezifisch)
| Command | Beschreibung |
|---------|--------------|
| `/context add [user] <context>` | Kontext setzen |
| `/context view [user]` | Kontext anzeigen |
| `/context clear [user]` | Kontext löschen |

### Knowledge (Server-weit)
| Command | Beschreibung | Berechtigung |
|---------|--------------|--------------|
| `/knowledge add <text>` | Wissen hinzufügen | Manage Server |
| `/knowledge list [limit]` | Wissen anzeigen | Manage Server |
| `/knowledge remove <id>` | Wissen löschen | Manage Server |

### Blacklist
| Command | Beschreibung | Berechtigung |
|---------|--------------|--------------|
| `/ai-blacklist add <user> [reason]` | User blocken | Manage Server |
| `/ai-blacklist remove <user>` | User entblocken | Manage Server |
| `/ai-blacklist list [limit]` | Blacklist anzeigen | Manage Server |

### Utilities
| Command | Beschreibung |
|---------|--------------|
| `/forget` | Löscht deine Konversationshistorie |
| `/stats` | Zeigt Server-Statistiken |

---

## ⚙️ Konfiguration

Die wichtigsten Einstellungen in `config/config.yml`:

```yaml
discord:
  token: "dein-token"
  guildId: "123456789"  # Optional: Schnellere Command-Updates
  activityType: "listening"  # playing, listening, watching, competing

openai:
  apiKey: "sk-..."
  model: "gpt-4o-mini"
  temperature: 0.7
  maxTokens: 320

ux:
  cooldownSeconds: 0  # 0 = kein Cooldown
  maxConversationMessages: 12  # 0 = Memory aus
```

---

## 🔧 Troubleshooting

| Problem | Lösung |
|---------|--------|
| SQLite Warning | `java --enable-native-access=ALL-UNNAMED -jar Nebi-1.0.jar` |
| HostnameUnverifiedException | Uhrzeit prüfen, Antivirus HTTPS-Scanning aus. Notlösung: `discord.insecureSkipHostnameVerification: true` |
| API-Key ungültig | Neuen Key von [platform.openai.com](https://platform.openai.com/api-keys) holen |
| Rate-Limit | Warte kurz oder upgrade deinen OpenAI Plan |
| Kein Guthaben | OpenAI Account aufladen |

---

## 📁 Projektstruktur

```
Nebi/
├── config/
│   └── config.yml          # Deine Konfiguration
├── data/
│   └── nebi.db             # SQLite Datenbank
├── src/main/java/io/nebuliton/
│   ├── Main.java           # Entry Point
│   ├── Database.java       # SQLite Wrapper
│   ├── ai/
│   │   ├── AIManager.java      # AI Logic + Auto-Learning
│   │   ├── OpenAIClient.java   # OpenAI API Client
│   │   ├── ContextStore.java   # Datenbank-Operationen
│   │   ├── Commands.java       # Slash Commands
│   │   ├── PingListener.java   # Message Handler
│   │   └── RateLimiter.java    # Cooldown Logic
│   └── config/
│       └── Config.java     # YAML Config Parser
└── target/
    └── Nebi-1.0.jar        # Fertiges JAR
```

---

## 📝 Hinweise

- **Tokens nicht commiten!** Füge `config/config.yml` und `data/` zur `.gitignore` hinzu
- **Logging** wird über `src/main/resources/logback.xml` gesteuert
- **Native-Access Auto-Restart** kann mit `-Dnebi.noNativeRestart=true` deaktiviert werden

---

## 📜 Lizenz

MIT – Mach damit was du willst.

---

<div align="center">

## ☁️ Hosted by

<a href="https://nebuliton.io">
  <img src="https://nebuliton.io/logo.png" alt="Nebuliton" width="200"/>
</a>

### [Nebuliton](https://nebuliton.io)

**Premium Server Hosting** – Schnell, zuverlässig, fair.

Gameserver • Discord Bots • Webhosting • VPS

[🚀 Jetzt starten](https://nebuliton.io)

</div>

