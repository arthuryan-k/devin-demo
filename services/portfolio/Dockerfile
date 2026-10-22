FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY portfolio ./portfolio
EXPOSE 8000
# Runs the read API and, when KAFKA_BOOTSTRAP_SERVERS is set, the events.<symbol> settlement consumer.
CMD ["uvicorn", "portfolio.api:app", "--host", "0.0.0.0", "--port", "8000"]
