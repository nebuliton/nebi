# Nebi

Ein kleiner, frecher Discord-Bot mit JDA, OpenAI API und SQLite. Er antwortet auf Pings (Mention), nutzt optionalen User-Kontext und gespeichertes Server-Wissen, und kann User fuer die AI blocken.

**Features**
- Antwortet auf Bot-Mentions mit einer sympathisch-lustigen AI-Antwort (einfach @Bot pingen).
- Merkt sich die letzten Unterhaltungen pro User (konfigurierbar).
- `/context add` setzt optionalen Kontext fuer einen User (z.B. Vorlieben, Running Gags).
- `/knowledge add` speichert serverweites Wissen, das die AI nutzen darf.
- `/ai-blacklist add` blockt User fuer AI-Antworten.
- Saubere UX: Limits, kleine Cooldowns, und sauberer Text ohne Mass-Pings.

**Setup**
- Java 21 installieren.
- Kopiere `config/config.example.yml` nach `config/config.yml` oder starte den Bot einmal, damit die Datei automatisch erstellt wird. Danach ausfuellen (Discord-Token, OpenAI-Key, optional `guildId` fuer schnellere Command-Updates).
- Discord Bot in der Developer Console erstellen und **Message Content Intent** aktivieren.
- Build: `mvn -DskipTests package`
- Run: `java -jar target/Nebi-1.0.jar` (optional: `java -jar target/Nebi-1.0.jar pfad/zur/config.yml`)

**Commands**
- `/context add [user] <context>` - Kontext setzen (User optional).
- `/context view [user]` - Kontext anzeigen.
- `/context clear [user]` - Kontext loeschen.
- `/knowledge add <text>` - Wissen speichern (Manage Server).
- `/knowledge list [limit]` - Wissen anzeigen (Manage Server).
- `/knowledge remove <id>` - Wissen loeschen (Manage Server).
- `/ai-blacklist add <user> [reason]` - User blocken (Manage Server).
- `/ai-blacklist remove <user>` - User entblocken (Manage Server).
- `/ai-blacklist list [limit]` - Blacklist anzeigen (Manage Server).

**Hinweise**
- Die SQLite-Datei liegt standardmaessig unter `data/nebi.db`.
- Wenn `discord.guildId` gesetzt ist, werden Commands nur fuer diesen Server registriert (schneller).
- Tokens nicht commiten. Wenn du willst, setz `config/config.yml` und `data/` auf die Gitignore.
- Memory: Mit `ux.maxConversationMessages` kannst du festlegen, wie viele Messages pro User gemerkt werden (0 = aus).
- Activity: Mit `discord.activityType` kannst du `playing`, `listening`, `watching` oder `competing` setzen.
- Logging: `src/main/resources/logback.xml` steuert das Log-Format und Farben. Override mit `-Dlogback.configurationFile=pfad/zur/logback.xml`.

**Troubleshooting**
- SQLite Warning: Starte mit `java --enable-native-access=ALL-UNNAMED -jar Nebi-1.0.jar`, falls dich die Warnung stoert.
- `HostnameUnverifiedException`: Pruefe Uhrzeit, Antivirus-HTTPS-Scanning oder Proxy. Notloesung: `discord.insecureSkipHostnameVerification: true` (unsicher).
- Native-Access Auto-Restart: Der Bot startet sich bei Bedarf neu, um die SQLite-Warnung zu vermeiden. Deaktivieren mit `-Dnebi.noNativeRestart=true`.
