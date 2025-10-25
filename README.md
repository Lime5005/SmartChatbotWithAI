
# 🧠 Smart Shopping Chatbot (Spring AI + Ollama)

A lightweight **Proof of Concept (PoC)** built with **Spring AI** and **Ollama**, simulating an intelligent shopping assistant that guides or recommends products based on user preferences.

---

## ⚙️ Prerequisites

You need to have **Ollama** running locally before starting the Spring Boot project.

### 1️⃣ Install Ollama
```bash
brew install --cask ollama
ollama pull llama3.1:8b
ollama pull nomic-embed-text
ollama list
ollama serve &
curl -s http://127.0.0.1:11434 | head
```

# 🚀 Run the Project
After Ollama is running:
```bash
mvn spring-boot:run
```
or run the main class directly in your IDE (the project’s entry point).

Once started, open your browser at:

http://localhost:8080

# 📄 Notes

Make sure both Ollama and your Spring Boot app are running simultaneously.

Default Ollama port: `11434`.

Adjust model names in `application.yml` if you use different ones.
