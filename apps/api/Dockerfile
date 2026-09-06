FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
COPY apps/api/package.json apps/api/package.json
COPY apps/mobile/package.json apps/mobile/package.json
COPY packages/contracts/package.json packages/contracts/package.json
COPY packages/domain/package.json packages/domain/package.json
RUN npm ci --include=dev
COPY tsconfig.base.json ./
COPY apps/api apps/api
COPY packages packages
RUN npm run build --workspace @odicto/contracts && npm run build --workspace @odicto/domain && npm run build --workspace @odicto/api

FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build /app/node_modules node_modules
COPY --from=build /app/apps/api/dist apps/api/dist
COPY --from=build /app/apps/api/package.json apps/api/package.json
COPY --from=build /app/packages/contracts packages/contracts
COPY --from=build /app/packages/domain packages/domain
USER node
EXPOSE 8080
CMD ["node", "apps/api/dist/server.js"]
