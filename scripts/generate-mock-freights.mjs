import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const markdown = readFileSync(resolve(root, "docs/mock-data.md"), "utf8");
const curatedRows = markdown.split("\n").filter((line) => /^freight-\d{3},/.test(line));

if (curatedRows.length !== 60) {
  throw new Error(`docs/mock-data.md에서 기존 화물 60건을 찾지 못했습니다: ${curatedRows.length}건`);
}

const routes = [
  ["REFRIGERATED", "신선식품", "서울특별시", "송파구", "부산광역시", "강서구", 5, 720000],
  ["GENERAL", "생활용품", "서울특별시", "송파구", "부산광역시", "강서구", 5, 680000],
  ["GENERAL", "전자부품", "서울특별시", "강동구", "부산광역시", "강서구", 4, 660000],
  ["FROZEN", "냉동수산물", "서울특별시", "강남구", "부산광역시", "해운대구", 4, 760000],
  ["GENERAL", "포장박스", "서울특별시", "마포구", "인천광역시", "부평구", 1, 160000],
  ["GENERAL", "기계부품", "경기도", "수원시", "서울특별시", "송파구", 2.5, 350000],
  ["CONSTRUCTION", "철근", "경기도", "화성시", "충청남도", "당진시", 11, 320000],
  ["GENERAL", "공산품", "인천광역시", "서구", "대전광역시", "유성구", 3.5, 380000],
  ["REFRIGERATED", "신선채소", "경기도", "평택시", "부산광역시", "사상구", 5, 650000],
  ["GENERAL", "자동차부품", "대전광역시", "유성구", "경기도", "수원시", 4, 410000],
  ["GENERAL", "산업자재", "울산광역시", "남구", "대구광역시", "달서구", 3, 300000],
  ["REFRIGERATED", "과일", "대구광역시", "달서구", "경상북도", "포항시", 2, 280000],
  ["GENERAL", "택배상자", "경상남도", "창원시", "부산광역시", "강서구", 4, 250000],
  ["FROZEN", "냉동만두", "강원특별자치도", "원주시", "서울특별시", "강남구", 2.5, 360000],
  ["CONSTRUCTION", "시멘트", "충청북도", "청주시", "세종특별자치시", "세종시", 10, 230000],
  ["GENERAL", "식자재상자", "충청남도", "천안시", "서울특별시", "송파구", 3, 330000],
  ["HAZARDOUS", "산업용용제", "인천광역시", "중구", "울산광역시", "남구", 8, 720000],
  ["GENERAL", "가구", "전라북도", "전주시", "대전광역시", "유성구", 4, 300000],
  ["GENERAL", "농산물상자", "광주광역시", "광산구", "전라남도", "여수시", 5, 320000],
  ["GENERAL", "포장재", "전라남도", "순천시", "광주광역시", "북구", 2, 270000],
  ["GENERAL", "가전제품", "경상북도", "구미시", "대구광역시", "북구", 3, 240000],
  ["GENERAL", "정밀부품", "세종특별자치시", "세종시", "충청남도", "아산시", 2.5, 230000],
  ["REFRIGERATED", "냉장육", "부산광역시", "사상구", "경상남도", "김해시", 2, 250000],
  ["GENERAL", "의류", "경기도", "고양시", "서울특별시", "마포구", 1.5, 200000],
];

const fareFactors = [0.68, 0.78, 0.86, 0.94, 1, 1.08, 1.18];
const generatedRows = [];

function kstDate(dayOffset, hour) {
  const day = 13 + dayOffset;
  return `2026-08-${String(day).padStart(2, "0")}T${String(hour).padStart(2, "0")}:00:00+09:00`;
}

for (let number = 61; number <= 500; number += 1) {
  const route = routes[(number - 61) % routes.length];
  const cycle = Math.floor((number - 61) / routes.length);
  const [cargoType, description, originSido, originSigungu, destinationSido, destinationSigungu, baseWeight, averageFare] = route;
  const weight = Math.max(0.5, baseWeight + ((cycle % 3) - 1) * 0.5);
  const fare = Math.round((averageFare * fareFactors[(number + cycle) % fareFactors.length]) / 10000) * 10000;
  const dayOffset = (number - 61) % 10;
  const loadingHour = 4 + ((number + cycle) % 11);
  const travelHours = 2 + ((number + route.length) % 8);
  const unloadingHour = Math.min(23, loadingHour + travelHours);
  const id = `freight-${String(number).padStart(3, "0")}`;
  generatedRows.push([
    id, cargoType, `${description}-${String(cycle + 1).padStart(2, "0")}`,
    originSido, originSigungu, destinationSido, destinationSigungu,
    Number.isInteger(weight) ? weight : weight.toFixed(1), fare,
    kstDate(dayOffset, loadingHour), kstDate(dayOffset, unloadingHour), "PENDING",
  ].join(","));
}

const header = "id,cargoType,cargoDescription,originSido,originSigungu,destinationSido,destinationSigungu,weightTon,offeredFareKrw,loadingAt,unloadingAt,status";
const output = `${header}\n${[...curatedRows, ...generatedRows].join("\n")}\n`;
const targets = [
  resolve(root, "docs/mock-freights.csv"),
  resolve(root, "backend/src/main/resources/mock/mock-freights.csv"),
];

for (const target of targets) {
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, output, "utf8");
}

console.log(`화물 ${curatedRows.length + generatedRows.length}건 생성 완료`);
