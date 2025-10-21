# BoardTTS: Board Text Reader for Android

## Description

BoardTTS is an Android application designed to assist visually impaired individuals by reading text written on whiteboards or other surfaces aloud. The app uses the device's camera to capture an image, employs a custom-trained **YOLO (You Only Look Once)** model to detect text blocks, and then uses **Optical Character Recognition (OCR)** to extract and read the text using Android's built-in Text-to-Speech (TTS) engine.

This project combines machine learning with a user-centric interface to provide a practical accessibility tool.

## Features

- **Real-time Text Detection:** Utilizes a custom YOLOv12n model optimized for TensorFlow Lite to quickly identify regions containing text.
- **Accurate OCR:** Leverages Google's ML Kit for an accurate text recognition from captured image segments.
- **Text-to-Speech Output:** Reads the detected text aloud clearly, providing immediate auditory feedback.
- **Simple User Interface:** A single-button interface makes the app extremely easy to operate.
- **Built for Accessibility:** Designed from the ground up with visually impaired users in mind, including audible feedback throughout the scanning process.

## How It Works

The application follows a multi-stage pipeline to deliver its functionality:

1.  **Capture:** The user points the camera and taps the "Scan" button. The app captures a high-resolution image using Android's CameraX library.
2.  **Pre-processing:** The captured image is automatically corrected for device rotation to ensure it is upright.
3.  **Detection (YOLO):** The upright image is fed into the YOLOv12n TFLite model, which returns bounding boxes for all detected text blocks.
4.  **Cropping:** The app uses the bounding boxes to crop the relevant text regions from the original high-resolution image.
5.  **Recognition (OCR):** Each cropped image is sent to Google's ML Kit Text Recognition service.
6.  **Read Aloud (TTS):** The recognized text from all blocks is combined into a single coherent message and spoken aloud by the Text-to-Speech engine.

## Setup and Installation

To build and run this project locally, follow these steps:

1.  **Clone the repository:**
2.  **Add the repository in Android Studio**
3.  **Run the project in a virtual or physical device**     
