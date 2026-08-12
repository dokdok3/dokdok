#!/usr/bin/env python3
"""Generate current Korean sido/sigungu representative coordinates.

Inputs:
  * Ministry of the Interior and Safety legal-dong full-code ZIP
  * Statistics Korea SGIS 2025 Q2 sido/sigungu/dong shapefiles

The 2026 Incheon and Hwaseong districts are rebuilt from their 2025
administrative-dong polygons. The merged Jeonnam-Gwangju province is rebuilt
from the former Gwangju and Jeonnam province polygons.
"""

from __future__ import annotations

import argparse
import csv
import io
from pathlib import Path
from zipfile import ZipFile

import shapefile
from pyproj import CRS, Transformer
from shapely.geometry import shape
from shapely.ops import transform, unary_union


CURRENT_SIDO_NAMES = {
    "11": "서울특별시",
    "12": "전남광주통합특별시",
    "26": "부산광역시",
    "27": "대구광역시",
    "28": "인천광역시",
    "30": "대전광역시",
    "31": "울산광역시",
    "36": "세종특별자치시",
    "41": "경기도",
    "43": "충청북도",
    "44": "충청남도",
    "47": "경상북도",
    "48": "경상남도",
    "50": "제주특별자치도",
    "51": "강원특별자치도",
    "52": "전북특별자치도",
}

GWANGJU_DISTRICTS = {"동구", "서구", "남구", "북구", "광산구"}

# 2026-07-01 districts reconstructed from the 2025-06-30 SGIS dong polygons.
# 운서동/아라동 were later split, so their 2025 parent polygons are used.
REBUILT_DISTRICTS = {
    "인천광역시 제물포구": {
        "신포동", "연안동", "신흥동", "도원동", "율목동", "동인천동", "개항동",
        "만석동", "화수1·화평동", "화수2동", "송현1·2동", "송현3동", "송림1동",
        "송림2동", "송림3·5동", "송림4동", "송림6동", "금창동",
    },
    "인천광역시 영종구": {"영종동", "영종1동", "영종2동", "운서동", "용유동"},
    "인천광역시 서해구": {
        "검암경서동", "연희동", "청라1동", "청라2동", "청라3동", "가정1동",
        "가정2동", "가정3동", "신현원창동", "석남1동", "석남2동", "석남3동",
        "가좌1동", "가좌2동", "가좌3동", "가좌4동",
    },
    "인천광역시 검단구": {
        "검단동", "불로대곡동", "원당동", "당하동", "오류왕길동", "마전동", "아라동",
    },
    "경기도 화성시 만세구": {
        "우정읍", "향남읍", "남양읍", "마도면", "송산면", "서신면", "팔탄면",
        "장안면", "양감면", "새솔동",
    },
    "경기도 화성시 효행구": {"봉담읍", "매송면", "비봉면", "정남면", "기배동"},
    "경기도 화성시 병점구": {"진안동", "병점1동", "병점2동", "반월동", "화산동"},
    "경기도 화성시 동탄구": {
        "동탄1동", "동탄2동", "동탄3동", "동탄4동", "동탄5동", "동탄6동",
        "동탄7동", "동탄8동", "동탄9동",
    },
}

REBUILT_PREFIX = {
    "인천광역시 제물포구": ("2301", "2302"),
    "인천광역시 영종구": ("2301",),
    "인천광역시 서해구": ("2308",),
    "인천광역시 검단구": ("2308",),
    "경기도 화성시 만세구": ("3124",),
    "경기도 화성시 효행구": ("3124",),
    "경기도 화성시 병점구": ("3124",),
    "경기도 화성시 동탄구": ("3124",),
}

OLD_SIDO_TO_CURRENT = {
    "서울특별시": "서울특별시",
    "부산광역시": "부산광역시",
    "대구광역시": "대구광역시",
    "인천광역시": "인천광역시",
    "대전광역시": "대전광역시",
    "울산광역시": "울산광역시",
    "세종특별자치시": "세종특별자치시",
    "경기도": "경기도",
    "강원특별자치도": "강원특별자치도",
    "충청북도": "충청북도",
    "충청남도": "충청남도",
    "전북특별자치도": "전북특별자치도",
    "경상북도": "경상북도",
    "경상남도": "경상남도",
    "제주특별자치도": "제주특별자치도",
}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--codes-zip", type=Path, required=True)
    parser.add_argument("--shapes-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def read_current_legal_codes(path: Path) -> list[tuple[str, str]]:
    with ZipFile(path) as archive:
        members = [name for name in archive.namelist() if not name.endswith("/")]
        if len(members) != 1:
            raise ValueError("legal-code ZIP must contain exactly one data file")
        raw = archive.read(members[0])

    text = None
    for encoding in ("cp949", "utf-8-sig"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            pass
    if text is None:
        raise ValueError("unsupported legal-code file encoding")

    rows: list[tuple[str, str]] = []
    reader = csv.DictReader(io.StringIO(text), delimiter="\t")
    for row in reader:
        if row["폐지여부"] == "존재":
            rows.append((row["법정동코드"], row["법정동명"].strip()))
    return rows


def current_sigungu(codes: list[tuple[str, str]]) -> list[tuple[str, str, bool]]:
    candidates = [
        (code[:5], name)
        for code, name in codes
        if code[5:] == "00000" and code[2:5] != "000"
    ]
    return [
        (
            code,
            name,
            not any(other_name.startswith(name + " ") for _, other_name in candidates),
        )
        for code, name in candidates
    ]


def read_shapes(path: Path, code_field: str, name_field: str):
    reader = shapefile.Reader(str(path), encoding="utf-8")
    source_crs = CRS.from_wkt(path.with_suffix(".prj").read_text())
    to_wgs84 = Transformer.from_crs(source_crs, "EPSG:4326", always_xy=True).transform
    result = []
    for shape_record in reader.iterShapeRecords():
        record = shape_record.record.as_dict()
        result.append(
            {
                "code": record[code_field],
                "name": record[name_field],
                "geometry": shape(shape_record.shape.__geo_interface__),
            }
        )
    return result, to_wgs84


def point_wgs84(geometry, transformer) -> tuple[str, str]:
    point = transform(transformer, geometry.representative_point())
    return f"{point.y:.7f}", f"{point.x:.7f}"


def area_type(level: str, name: str) -> str:
    if level == "SIDO":
        for suffix in ("특별자치시", "특별자치도", "통합특별시", "특별시", "광역시", "도"):
            if name.endswith(suffix):
                return suffix
    last = name.split()[-1]
    if last.endswith("특별자치시"):
        return "특별자치시"
    return last[-1]


def make_rows(codes_zip: Path, shapes_dir: Path) -> list[dict[str, str]]:
    codes = read_current_legal_codes(codes_zip)
    legal_sigungu = current_sigungu(codes)

    sido_shapes, transformer = read_shapes(
        shapes_dir / "bnd_sido_00_2025_2Q.shp", "SIDO_CD", "SIDO_NM"
    )
    sigungu_shapes, _ = read_shapes(
        shapes_dir / "bnd_sigungu_00_2025_2Q.shp", "SIGUNGU_CD", "SIGUNGU_NM"
    )
    dong_shapes, _ = read_shapes(
        shapes_dir / "bnd_dong_00_2025_2Q.shp", "ADM_CD", "ADM_NM"
    )

    old_sido_by_code = {item["code"]: item["name"] for item in sido_shapes}
    old_sido_geometry = {item["name"]: item for item in sido_shapes}
    old_sigungu = {
        (old_sido_by_code[item["code"][:2]], item["name"]): item
        for item in sigungu_shapes
    }

    rows: list[dict[str, str]] = []
    for code, name in CURRENT_SIDO_NAMES.items():
        if name == "전남광주통합특별시":
            components = [old_sido_geometry["광주광역시"], old_sido_geometry["전라남도"]]
            geometry = unary_union([item["geometry"] for item in components])
            method = "SGIS_2025_SIDO_UNION_POINT_ON_SURFACE"
        else:
            components = [old_sido_geometry[name]]
            geometry = components[0]["geometry"]
            method = "SGIS_2025_POINT_ON_SURFACE"
        latitude, longitude = point_wgs84(geometry, transformer)
        rows.append(
            {
                "area_level": "SIDO",
                "area_code": code,
                "parent_code": "",
                "area_type": area_type("SIDO", name),
                "is_leaf": "false",
                "sido_name": name,
                "sigungu_name": "",
                "full_name": name,
                "latitude": latitude,
                "longitude": longitude,
                "coordinate_method": method,
                "source_boundary_codes": "|".join(item["code"] for item in components),
                "boundary_base_date": "2025-06-30",
            }
        )

    for code, full_name, is_leaf in legal_sigungu:
        sido_name = CURRENT_SIDO_NAMES[code[:2]]
        short_name = full_name.removeprefix(sido_name).strip() or sido_name

        if not is_leaf:
            old_sido_name = sido_name
            parent_component = old_sigungu.get((old_sido_name, short_name))
            if parent_component is not None:
                components = [parent_component]
                geometry = parent_component["geometry"]
                method = "SGIS_2025_POINT_ON_SURFACE"
            else:
                components = [
                    item
                    for (province, name), item in old_sigungu.items()
                    if province == old_sido_name and name.startswith(short_name + " ")
                ]
                if not components:
                    raise ValueError(f"no child boundaries for {full_name}")
                geometry = unary_union([item["geometry"] for item in components])
                method = "SGIS_2025_SIGUNGU_UNION_POINT_ON_SURFACE"
        elif full_name in REBUILT_DISTRICTS:
            expected_names = REBUILT_DISTRICTS[full_name]
            prefixes = REBUILT_PREFIX[full_name]
            components = [
                item
                for item in dong_shapes
                if item["name"] in expected_names
                and any(item["code"].startswith(prefix) for prefix in prefixes)
            ]
            found = {item["name"] for item in components}
            if found != expected_names:
                raise ValueError(f"dong mismatch for {full_name}: {sorted(expected_names - found)}")
            geometry = unary_union([item["geometry"] for item in components])
            method = "SGIS_2025_DONG_UNION_POINT_ON_SURFACE"
        else:
            old_sido_name = sido_name
            old_short_name = short_name
            if sido_name == "전남광주통합특별시":
                old_sido_name = "광주광역시" if short_name in GWANGJU_DISTRICTS else "전라남도"
            elif sido_name == "세종특별자치시":
                old_short_name = "세종시"
            component = old_sigungu.get((old_sido_name, old_short_name))
            if component is None:
                raise ValueError(f"no 2025 boundary match for {full_name}")
            components = [component]
            geometry = component["geometry"]
            method = "SGIS_2025_POINT_ON_SURFACE"

        latitude, longitude = point_wgs84(geometry, transformer)
        rows.append(
            {
                "area_level": "SIGUNGU",
                "area_code": code,
                "parent_code": code[:2],
                "area_type": area_type("SIGUNGU", full_name),
                "is_leaf": str(is_leaf).lower(),
                "sido_name": sido_name,
                "sigungu_name": short_name,
                "full_name": full_name,
                "latitude": latitude,
                "longitude": longitude,
                "coordinate_method": method,
                "source_boundary_codes": "|".join(item["code"] for item in components),
                "boundary_base_date": "2025-06-30",
            }
        )

    return sorted(rows, key=lambda row: (row["area_code"], row["area_level"]))


def validate(rows: list[dict[str, str]]) -> None:
    sido = [row for row in rows if row["area_level"] == "SIDO"]
    sigungu = [row for row in rows if row["area_level"] == "SIGUNGU"]
    if len(sido) != 16 or len(sigungu) != 269:
        raise ValueError(f"unexpected counts: sido={len(sido)}, sigungu={len(sigungu)}")
    if sum(row["is_leaf"] == "true" for row in sigungu) != 256:
        raise ValueError("unexpected leaf sigungu count")
    if len({(row["area_level"], row["area_code"]) for row in rows}) != len(rows):
        raise ValueError("duplicate area code")
    sido_codes = {row["area_code"] for row in sido}
    for row in sigungu:
        if row["parent_code"] not in sido_codes:
            raise ValueError(f"missing parent for {row['full_name']}")
    for row in rows:
        lat, lon = float(row["latitude"]), float(row["longitude"])
        if not (32.0 <= lat <= 39.5 and 124.0 <= lon <= 132.0):
            raise ValueError(f"coordinate outside Korea range: {row['full_name']}")


def main() -> None:
    args = arguments()
    rows = make_rows(args.codes_zip, args.shapes_dir)
    validate(rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} rows to {args.output} (16 sido, 269 sigungu; 256 leaves)")


if __name__ == "__main__":
    main()
