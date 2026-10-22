# Build context: services/ (the risk service shares the portfolio ledger library).
FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY portfolio/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY portfolio/portfolio ./portfolio
COPY risk/risk ./risk
EXPOSE 8000
CMD ["uvicorn", "risk.api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
