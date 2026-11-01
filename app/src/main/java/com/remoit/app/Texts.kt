package com.remoit.app

object Texts {
    const val OWNER_IG = "@sharan.in__"
    const val IG_URL = "https://www.instagram.com/sharan.in__/"

    val termsIntro = "Please read these terms carefully before you use remoit. They explain what you may do with the app and with the files it creates."

    /** Title to body. */
    val terms = listOf(
        "1. About remoit" to
            "remoit is a text-removal app for videos and images, owned and operated by Sharan (Instagram: $OWNER_IG). You mark the text, and remoit rebuilds the picture behind it from the colours around it, on your own phone.",
        "2. Only edit what is yours" to
            "Use remoit only on videos and images that you own or have permission to edit. Do not use it to remove another person's watermark, credit or copyright notice, or to pass off someone else's work as your own. You are responsible for what you create and share.",
        "3. Quality" to
            "Your video keeps its original resolution and frame rate, and its original audio is copied without any change. The picture is re-encoded at a higher bitrate than the source so it stays as sharp as possible. HDR / 10-bit videos are not supported yet.",
        "4. Length and time" to
            "A video can be up to 2 hours 30 minutes long. Long videos take a long time and use battery and storage. Keep the phone charged and keep enough free space. Processing continues in the background.",
        "5. Credits and your device" to
            "No Gmail, e-mail address or phone number is required. Credits are linked directly to your mobile device. Each device receives 5 credits every 24 hours, and one finished video or image costs 1 credit. Trying to bypass, reset or share credits is not allowed.",
        "6. Your files and privacy" to
            "Everything happens on your phone. Your videos and images are never uploaded anywhere. Your results and project history stay on your device until you delete them.",
        "7. Honest limits" to
            "remoit rebuilds the picture behind the text from nearby pixels. On smooth or plain backgrounds the result is almost invisible. On very busy backgrounds a faint trace can remain, and the result depends on how well you mark the text.",
        "8. No warranty" to
            "remoit is provided \"as is\", without any guarantee. The owner is not responsible for lost files, failed jobs or any loss that comes from using the app. Questions? Message $OWNER_IG on Instagram.",
        "9. Changes" to
            "These terms may be updated from time to time. Continuing to use the app means that you accept the updated terms."
    )

    const val termsOutro = "By pressing Accept, you confirm that you have read these terms and that you agree to them."

    const val aboutTitle = "About this app"
    const val aboutBody =
        "remoit removes text, logos, dates and captions from your videos and images, and the result does not look erased.\n\n" +
        "You mark the text with a box or a pen. remoit then finds the real text pixels, rebuilds the background from the colours and texture around them, adds matching grain, and keeps the rest of the picture untouched, frame by frame."

    val aboutPoints = listOf(
        "Upload" to "Pick an image or a video up to 2 h 30 min.",
        "Mark" to "Tap to make a box, or doodle with the pen.",
        "Process" to "remoit rebuilds the pixels behind the text.",
        "Download" to "Same resolution, same frame rate, original audio."
    )

    const val aboutHonest =
        "remoit rebuilds what is behind the text from the pixels around it. It works best on small text and logos."
}
