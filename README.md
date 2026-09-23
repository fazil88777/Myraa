# MYRA — AI Voice Assistant

MYRA ek Android voice assistant app hai jo Gemini Live se baat karti hai, Roman Urdu me jawab deti hai, aur phone control kar sakti hai — jaise apps kholna, call karna aur SMS bhejna.

## Setup

1. **GitHub account:** github.com par free account banao.
2. **Files upload:** Is project ki saari files apne naye GitHub repo me upload karo (repo me **Add file → Upload files** par click karo).
3. **API key:** Google AI Studio (aistudio.google.com) se free Gemini API key lo. Key code me mat likho — app kholne ke baad API key wale box me daal kar **Save** dabao, wo phone me save ho jayegi.
4. **APK build:** GitHub repo me **Actions** tab kholo → **"Build MYRA APK"** workflow → **Run workflow** dabao → build poora hone par **Artifacts** me se **myra-apk** download karo.

## Install

- Phone **Settings** me **"Install unknown apps"** allow karo, phir APK install karo.
- App kholo aur saari permissions grant karo.
- **"Permissions"** button dabao — overlay aur accessibility settings khulenge, wahan MYRA ko **ON** karo.
- Call screening ke liye **"Call Role"** button dabao.

## Note

- Pehli cloud build fail ho sakti hai, ye normal hai — error parh kar fix karo aur dobara run karo.
- Call answer aur accessibility ke liye phone ki settings me manually permission dena zaroori hai.
