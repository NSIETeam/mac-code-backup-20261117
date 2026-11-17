FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY server/ server/
COPY agent/core/ agent/core/
RUN dotnet restore server/Siyue.Server.csproj --locked-mode && dotnet publish server/Siyue.Server.csproj -c Release --no-restore -o /out
FROM mcr.microsoft.com/dotnet/aspnet:10.0
USER root
RUN apt-get update && apt-get install -y --no-install-recommends nodejs python3 python3-venv python3-pil libgl1 libreoffice-writer libreoffice-math poppler-utils poppler-data tesseract-ocr tesseract-ocr-chi-sim fonts-noto-cjk fonts-stix fontconfig && rm -rf /var/lib/apt/lists/*
COPY server/pdf-ocr-requirements.txt /tmp/pdf-ocr-requirements.txt
RUN python3 -m venv /opt/ocr && /opt/ocr/bin/pip install --no-cache-dir --require-hashes -r /tmp/pdf-ocr-requirements.txt
COPY deploy/fonts/ /usr/local/share/fonts/siyue/
RUN fc-cache -f && fc-match 'Source Han Serif SC'
WORKDIR /app
COPY --from=build /out .
RUN mkdir /work && chown app:app /work
USER app
ENV ASPNETCORE_HTTP_PORTS=8080 TMPDIR=/work
ENTRYPOINT ["dotnet", "Siyue.Server.dll"]
