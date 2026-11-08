# Artwork for the screens

Drop the PNG files into `android/app/src/main/res/drawable-nodpi/`, one file per icon, with the exact names below.
Nothing else has to change. A screen draws a simple placeholder icon until its file exists.

Android resource names use lowercase letters, digits and underscores only.
Use square images, 512 by 512 pixels, with a transparent background.

| File | Where it appears |
| --- | --- |
| `icon_send.png` | Send card on the home screen |
| `icon_receive.png` | Receive card on the home screen |
| `icon_category_documents.png` | Documents card on the send screen |
| `icon_category_downloads.png` | Downloads card on the send screen |
| `icon_category_videos.png` | Videos card on the send screen |
| `icon_category_audio.png` | Audio card on the send screen |
| `icon_category_apps.png` | Apps card on the send screen |
| `icon_category_images.png` | Images card on the send screen |

The code that looks the files up is `IsoIcon` in `ui/components/Components.kt`.
