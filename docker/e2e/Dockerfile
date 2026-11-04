FROM mcr.microsoft.com/playwright:v1.64.1-noble
WORKDIR /qa
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY playwright.config.js ./
COPY tests ./tests
CMD ["npx", "playwright", "test"]
